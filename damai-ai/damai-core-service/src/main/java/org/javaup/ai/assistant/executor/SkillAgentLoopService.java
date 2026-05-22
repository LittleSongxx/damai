package org.javaup.ai.assistant.executor;

import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.assistant.AssistantEventTypes;
import org.javaup.ai.assistant.AssistantRunService;
import org.javaup.ai.assistant.AssistantSkillDescriptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能级 Agentic 决策循环 —— 遵循 Anthropic "Building Effective Agents" 中 Agent 模式定义。
 *
 * <p>设计参考：
 * <ul>
 *   <li>Anthropic (2024.12) "Building Effective Agents": Agent = LLM 在循环中动态指导自身工具使用和流程，
 *       "用反馈环来维持控制，当工具调用不满足需求时自动进入替代策略"。</li>
 *   <li>LangGraph ReAct pattern: 当单次工具调用不足以解决问题时，agent 应 self-correct 并尝试不同方法。</li>
 * </ul>
 *
 * <p>Spring AI ChatClient 已内置 Tool Calling 循环。本服务在其外层增加"答案不完整检测 + 替代策略重试"。
 * 仅当 LLM 的最终答案被判定为不完整时触发重试，而非每一步都干预。
 */
@Slf4j
@Service
public class SkillAgentLoopService {

    private static final int MAX_RETRY_STEPS = 4;

    private final AssistantRunService assistantRunService;

    public SkillAgentLoopService(AssistantRunService assistantRunService) {
        this.assistantRunService = assistantRunService;
    }

    /**
     * 执行带替代策略回退的 Agentic 调用。
     *
     * @param chatClient       已绑定工具回调的 ChatClient（Spring AI 自动处理 Tool Calling 内循环）
     * @param runId            当前 Run ID
     * @param userPrompt       用户原始问题 + 上下文
     * @param descriptor       当前 Skill 描述符
     * @param alternativeHints 替代策略提示（如"若无该城市结果，建议搜索全国范围"）
     * @return 最终答案字符串
     */
    public String executeWithRetry(ChatClient chatClient,
                                   String runId,
                                   String userPrompt,
                                   AssistantSkillDescriptor descriptor,
                                   List<String> alternativeHints) {
        int step = 0;
        String currentPrompt = userPrompt;
        String finalAnswer = null;

        while (step < MAX_RETRY_STEPS) {
            step++;
            log.info("SkillAgentLoop step={}/{} runId={} skillId={}",
                    step, MAX_RETRY_STEPS, runId, descriptor == null ? "unknown" : descriptor.getSkillId());

            Map<String, Object> stepPayload = new HashMap<>();
            stepPayload.put("runId", runId);
            stepPayload.put("step", step);
            stepPayload.put("skillId", descriptor == null ? "" : descriptor.getSkillId());

            try {
                // Spring AI ChatClient 在此内部处理完整的 Tool Calling 循环
                // （LLM 决定调工具 → 框架执行工具 → 结果回填 → LLM 再决策 → ... → 最终答案）
                String text = chatClient.prompt()
                        .user(currentPrompt)
                        .call()
                        .content();

                if (text == null || text.isBlank()) {
                    stepPayload.put("action", "empty_response");
                    assistantRunService.appendEvent(runId, AssistantEventTypes.AGENT_STEP, stepPayload);
                    break;
                }

                if (isIncompleteAnswer(text) && step < MAX_RETRY_STEPS) {
                    // 答案不完整 → 触发替代策略重试（Anthropic: "Agent 应评估环境反馈并动态调整"）
                    stepPayload.put("action", "retry_with_alternative");
                    stepPayload.put("observation", text.length() > 300 ? text.substring(0, 300) + "..." : text);
                    assistantRunService.appendEvent(runId, AssistantEventTypes.AGENT_STEP, stepPayload);

                    log.info("SkillAgentLoop retry triggered: runId={}, step={}, answer snippet={}",
                            runId, step, text.length() > 200 ? text.substring(0, 200) : text);
                    currentPrompt = buildReplanPrompt(userPrompt, text, alternativeHints, step);
                } else {
                    // 答案完整 或 已达最大步数 → 输出
                    stepPayload.put("action", "final_answer");
                    stepPayload.put("step", step);
                    assistantRunService.appendEvent(runId, AssistantEventTypes.AGENT_STEP, stepPayload);
                    finalAnswer = text;
                    break;
                }
            } catch (Exception e) {
                log.warn("SkillAgentLoop step {} failed: runId={}, error={}", step, runId, e.getMessage());
                stepPayload.put("action", "error");
                stepPayload.put("observation", "Step failed: " + e.getMessage());
                assistantRunService.appendEvent(runId, AssistantEventTypes.AGENT_STEP, stepPayload);
                break;
            }
        }

        if (finalAnswer == null) {
            finalAnswer = "我尝试了多步分析但仍未完全满足您的问题。建议您补充更具体的信息，例如城市、日期或演出类型，我可以再次帮您查找。";
        }
        return finalAnswer;
    }

    /**
     * 判断回答是否不完整 —— 参考 Anthropic "Agents dynamically direct their own processes"。
     *
     * <p>识别模式：LLM 未能提供用户所需的具体信息（未找到节目、无可用票档、需要更多输入等）。
     */
    private boolean isIncompleteAnswer(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String lower = text.toLowerCase();
        boolean hasNegativeIndicator = lower.contains("没有找到") || lower.contains("暂无相关")
                || lower.contains("无相关") || lower.contains("已售罄") || lower.contains("暂时无法")
                || lower.contains("不确定能否") || lower.contains("未能查询到");
        boolean hasPositiveIndicator = lower.contains("为您找到") || lower.contains("订单已创建")
                || lower.contains("购票预览") || lower.contains("以下是") || lower.contains("推荐");
        return hasNegativeIndicator && !hasPositiveIndicator;
    }

    /**
     * 构建替代策略重试 prompt —— 参考 Anthropic "Orchestrator-Workers" 模式。
     *
     * <p>Worker（本方法）收到 Orchestrator 的反馈后，基于替代策略重新生成答案。
     */
    private String buildReplanPrompt(String originalPrompt, String lastAnswer, List<String> alternativeHints, int step) {
        StringBuilder builder = new StringBuilder();
        builder.append("【注意】你上一轮的回答未能完全解决用户的问题。\n\n");
        builder.append("上一轮回答（节选）：\n").append(lastAnswer, 0, Math.min(600, lastAnswer.length()));
        builder.append("\n\n").append("请尝试以下替代策略（第").append(step).append("轮自动重试）：\n");
        if (alternativeHints != null && !alternativeHints.isEmpty()) {
            for (int i = 0; i < alternativeHints.size(); i++) {
                builder.append(i + 1).append(". ").append(alternativeHints.get(i)).append("\n");
            }
        } else {
            builder.append("• 扩大搜索范围\n• 尝试不同的表达方式\n• 如果仍无结果，诚实说明\n");
        }
        builder.append("\n———原始问题与上下文———\n").append(originalPrompt);
        builder.append("\n\n请给出一个全新的、完整的回答。如果尝试了替代策略仍无结果，请诚实告知并提供下一步建议。");
        return builder.toString();
    }
}
