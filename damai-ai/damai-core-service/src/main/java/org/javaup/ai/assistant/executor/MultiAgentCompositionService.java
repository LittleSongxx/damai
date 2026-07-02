package org.javaup.ai.assistant.executor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.assistant.AssistantEventTypes;
import org.javaup.ai.assistant.AssistantRunService;
import org.javaup.ai.assistant.AssistantSkill;
import org.javaup.ai.assistant.AssistantSkillContext;
import org.javaup.ai.assistant.AssistantSkillRegistry;
import org.javaup.ai.assistant.AssistantSkillResult;
import org.javaup.ai.context.AiRequestContextHolder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * 多 Agent 组合编排 —— 遵循 CrewAI 层次化流程 + LangGraph 子图组合设计。
 *
 * <p>CrewAI 核心模式:
 * <ul>
 *   <li>Sequential: 按序执行，前一步输出作为后一步输入</li>
 *   <li>Hierarchical: 由编排器决定下一步执行哪个 Agent</li>
 *   <li>Parallel: 独立子任务并发执行，结果汇总</li>
 * </ul>
 *
 * <p>LangGraph 子图组合: 每个 Agent 可视为一个子图节点，
 * 编排器负责节点间的边（顺序、并行、条件）。
 *
 * <p>组合能力:
 * <ul>
 *   <li>并行执行: {@link #executeParallel}</li>
 *   <li>条件分支: {@link CompositionStep} 支持 skipWhen 条件</li>
 *   <li>上下文传递: 每步结果可注入后续步骤的上下文中</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiAgentCompositionService {

    private final AssistantSkillRegistry skillRegistry;
    private final AssistantRunService runService;
    private final AssistantMessageEmitter messageEmitter;

    private static final ExecutorService PARALLEL_EXECUTOR = Executors.newCachedThreadPool(
            r -> { Thread t = new Thread(r, "multi-agent-parallel"); t.setDaemon(true); return t; });

    /**
     * 顺序执行。
     */
    public String executeSequence(String runId, String chatId, List<String> skillIds,
                                   AssistantSkillContext baseContext) {
        List<CompositionStep> steps = skillIds.stream()
                .map(id -> new CompositionStep(id, id, null))
                .toList();
        return executeSteps(runId, chatId, steps, baseContext, CompositionMode.SEQUENTIAL);
    }

    /**
     * 按步执行组合链，支持顺序/并行/条件。
     *
     * @param steps  组合步骤列表
     * @param mode   执行模式
     */
    public String executeSteps(String runId, String chatId, List<CompositionStep> steps,
                                AssistantSkillContext baseContext, CompositionMode mode) {
        return switch (mode) {
            case SEQUENTIAL -> executeSequential(runId, chatId, steps, baseContext);
            case PARALLEL -> executeParallel(runId, chatId, steps, baseContext);
            case CONDITIONAL -> executeConditional(runId, chatId, steps, baseContext);
        };
    }

    /**
     * 顺序执行: 按 steps 列表顺序逐一执行，每步结果可注入后续上下文。
     *
     * <p>遵循 CrewAI Sequential Process。
     */
    private String executeSequential(String runId, String chatId, List<CompositionStep> steps,
                                      AssistantSkillContext baseContext) {
        List<String> results = new ArrayList<>();
        Map<String, String> previousResults = new LinkedHashMap<>();
        AssistantSkillContext currentContext = baseContext;

        for (int i = 0; i < steps.size(); i++) {
            CompositionStep step = steps.get(i);
            if (step.skipWhen != null && step.skipWhen.test(previousResults)) {
                log.info("Skipping step {} due to condition", step.skillId);
                results.add(String.format("[%s] SKIPPED", step.label));
                continue;
            }
            try {
                AssistantSkill skill = skillRegistry.getRequired(step.skillId);
                // 上下文传递: 将前一步结果注入当前上下文
                if (!previousResults.isEmpty() && step.inheritContext) {
                    currentContext = enrichContext(baseContext, previousResults);
                }
                AssistantSkillResult result = skill.execute(currentContext);
                String stepResult = result.getResponseSummary() != null ? result.getResponseSummary() : "completed";
                String formatted = String.format("[%s] %s", step.label, stepResult);
                results.add(formatted);
                previousResults.put(step.skillId, stepResult);

                runService.appendEvent(runId, AssistantEventTypes.AGENT_STEP, Map.of(
                        "compositionStep", i + 1,
                        "totalSteps", steps.size(),
                        "skillId", step.skillId,
                        "label", step.label,
                        "mode", "SEQUENTIAL",
                        "result", stepResult
                ));
            } catch (Exception e) {
                log.warn("Composition step failed: skillId={}, error={}", step.skillId, e.getMessage());
                results.add(String.format("[%s] ERROR: %s", step.label, e.getMessage()));
                if (step.failFast) {
                    break;
                }
            }
        }

        String composed = String.join("\n", results);
        messageEmitter.emitMessage(runId, chatId, composed);
        return composed;
    }

    /**
     * 并行执行: 将所有步骤并发提交，收集结果后汇总。
     *
     * <p>遵循 CrewAI 的并行任务委派模式: 独立子任务由不同 Agent 同时处理，
     * 编排器等待全部完成后汇总。适用于: 同时查知识库 + 查业务数据 + 查运维指标。
     */
    private String executeParallel(String runId, String chatId, List<CompositionStep> steps,
                                    AssistantSkillContext baseContext) {
        List<CompletableFuture<String>> futures = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            final int stepIndex = i;
            final CompositionStep step = steps.get(i);
            futures.add(CompletableFuture.supplyAsync(() -> {
                AiRequestContextHolder.set(AiRequestContextHolder.get());
                try {
                    AssistantSkill skill = skillRegistry.getRequired(step.skillId);
                    AssistantSkillResult result = skill.execute(baseContext);
                    String stepResult = result.getResponseSummary() != null ? result.getResponseSummary() : "completed";
                    runService.appendEvent(runId, AssistantEventTypes.AGENT_STEP, Map.of(
                            "compositionStep", stepIndex + 1,
                            "totalSteps", steps.size(),
                            "skillId", step.skillId,
                            "label", step.label,
                            "mode", "PARALLEL",
                            "result", stepResult
                    ));
                    return String.format("[%s] %s", step.label, stepResult);
                } catch (Exception e) {
                    log.warn("Parallel composition step failed: skillId={}, error={}", step.skillId, e.getMessage());
                    return String.format("[%s] ERROR: %s", step.label, e.getMessage());
                }
            }, PARALLEL_EXECUTOR));
        }

        List<String> results = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            try {
                results.add(futures.get(i).get(30, TimeUnit.SECONDS));
            } catch (Exception e) {
                results.add(String.format("[%s] TIMEOUT: %s", steps.get(i).label, e.getMessage()));
            }
        }

        String composed = String.join("\n", results);
        messageEmitter.emitMessage(runId, chatId, composed);
        return composed;
    }

    /**
     * 条件执行: 执行第一个满足条件的步骤。
     *
     * <p>类似 LangGraph 的条件边: 根据前序结果选择下一节点。
     */
    private String executeConditional(String runId, String chatId, List<CompositionStep> steps,
                                       AssistantSkillContext baseContext) {
        Map<String, String> emptyPrevious = Map.of();

        for (int i = 0; i < steps.size(); i++) {
            CompositionStep step = steps.get(i);
            if (step.skipWhen == null || !step.skipWhen.test(emptyPrevious)) {
                try {
                    AssistantSkill skill = skillRegistry.getRequired(step.skillId);
                    AssistantSkillResult result = skill.execute(baseContext);
                    String stepResult = result.getResponseSummary() != null ? result.getResponseSummary() : "completed";
                    String formatted = String.format("[%s] %s", step.label, stepResult);

                    runService.appendEvent(runId, AssistantEventTypes.AGENT_STEP, Map.of(
                            "compositionStep", i + 1,
                            "totalSteps", steps.size(),
                            "skillId", step.skillId,
                            "label", step.label,
                            "mode", "CONDITIONAL",
                            "result", stepResult
                    ));
                    messageEmitter.emitMessage(runId, chatId, formatted);
                    return formatted;
                } catch (Exception e) {
                    log.warn("Conditional composition step failed: skillId={}, error={}", step.skillId, e.getMessage());
                }
            }
        }

        String fallback = "No matching step found for conditional composition";
        messageEmitter.emitMessage(runId, chatId, fallback);
        return fallback;
    }

    /**
     * 将前一步结果注入上下文 —— 遵循 LangGraph 状态传递。
     */
    private AssistantSkillContext enrichContext(AssistantSkillContext baseContext, Map<String, String> previousResults) {
        String previousSummary = String.join("; ", previousResults.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .toList());
        return AssistantSkillContext.builder()
                .run(baseContext.getRun())
                .user(baseContext.getUser())
                .message(baseContext.getMessage() + "\n\n上一步结果: " + previousSummary)
                .clientContext(baseContext.getClientContext())
                .memoryContext(baseContext.getMemoryContext())
                .userProfileContext(baseContext.getUserProfileContext())
                .skillDescriptor(baseContext.getSkillDescriptor())
                .skillResources(baseContext.getSkillResources())
                .build();
    }

    // ------- 内部类型 -------

    public enum CompositionMode {
        SEQUENTIAL,
        PARALLEL,
        CONDITIONAL
    }

    /**
     * 组合步骤定义 —— 遵循 CrewAI Task + LangGraph StateGraph 节点概念。
     */
    public static class CompositionStep {
        final String skillId;
        final String label;
        final Predicate<Map<String, String>> skipWhen;
        boolean inheritContext = true;
        boolean failFast;

        public CompositionStep(String skillId, String label, Predicate<Map<String, String>> skipWhen) {
            this.skillId = skillId;
            this.label = label;
            this.skipWhen = skipWhen;
        }

        public CompositionStep inheritContext(boolean inherit) {
            this.inheritContext = inherit;
            return this;
        }

        public CompositionStep failFast(boolean failFast) {
            this.failFast = failFast;
            return this;
        }
    }
}
