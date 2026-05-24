package org.javaup.ai.assistant;

import org.javaup.ai.context.AiUserContext;
import org.javaup.ai.dto.AssistantRunCreateRequest;
import org.javaup.ai.entity.AiRun;
import org.javaup.ai.security.AiPermissionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AssistantExecutionPlanner {

    private static final double SKILL_CONFIDENCE_THRESHOLD = 0.65D;

    private final AssistantRouteService routeService;
    private final AiPermissionService aiPermissionService;
    private final AssistantSkillSelector skillSelector;

    public AssistantExecutionPlanner(AssistantRouteService routeService, AiPermissionService aiPermissionService) {
        this(routeService, aiPermissionService, null);
    }

    @Autowired
    public AssistantExecutionPlanner(AssistantRouteService routeService, AiPermissionService aiPermissionService, AssistantSkillSelector skillSelector) {
        this.routeService = routeService;
        this.aiPermissionService = aiPermissionService;
        this.skillSelector = skillSelector;
    }

    public AssistantExecutionPlan plan(AiRun run, AiUserContext user, AssistantRunCreateRequest request) {
        // Sentiment pre-check: strong negative sentiment → reassurance + escalation suggestion
        SentimentCheckResult sentiment = checkSentiment(request.getMessage());
        if (sentiment.isStrongNegative()) {
            return AssistantExecutionPlan.builder()
                    .runId(run.getRunId())
                    .conversationId(run.getConversationId())
                    .originalMessage(request.getMessage())
                    .clientContext(request.getClientContext())
                    .executionMode(AssistantExecutionMode.CLARIFICATION)
                    .routeDecision(AssistantRouteDecision.builder()
                            .routeType(AssistantRouteType.BUSINESS)
                            .reason("sentiment_negative")
                            .fromFallback(false)
                            .clarificationRequired(true)
                            .clarificationPrompt(sentiment.responseMessage())
                            .clarificationOptions(List.of("转人工客服", "继续使用购票助手", "继续咨询规则问题"))
                            .build())
                    .responseMessage(sentiment.responseMessage())
                    .options(List.of("转人工客服", "继续使用购票助手", "继续咨询规则问题"))
                    .reason("sentiment_negative")
                    .build();
        }

        AssistantRouteDecision decision = resolveRouteDecision(request.getMessage(), request.getClientContext());
        if (!aiPermissionService.canAccessRoute(user, decision.getRouteType())) {
            decision = AssistantRouteDecision.builder()
                    .routeType(AssistantRouteType.BUSINESS)
                    .reason("forbidden:ops")
                    .fromFallback(false)
                    .clarificationRequired(true)
                    .clarificationPrompt("当前账号无权访问 AI 运维能力。你可以继续使用购票业务助手或规则知识助手。")
                    .clarificationOptions(List.of("帮我查询和推荐演出", "咨询退票、入场或购票规则"))
                    .build();
        }
        AssistantSkillDecision skillDecision = null;
        boolean clarificationRequired = Boolean.TRUE.equals(decision.getClarificationRequired());
        if (!clarificationRequired && skillSelector != null) {
            skillDecision = skillSelector.select(decision.getRouteType(), user, request);
            if (skillDecision.getDescriptor() == null || skillDecision.getReason().startsWith("skill:")) {
                return skillClarificationPlan(run, request, decision, skillDecision);
            }
            if (!Boolean.TRUE.equals(skillDecision.getFromHint())
                    && skillDecision.getConfidence() != null
                    && skillDecision.getConfidence() < SKILL_CONFIDENCE_THRESHOLD) {
                return lowConfidenceSkillPlan(run, request, decision, skillDecision);
            }
            if (skillDecision.getDescriptor().getRouteType() != decision.getRouteType()) {
                decision = AssistantRouteDecision.builder()
                        .routeType(skillDecision.getDescriptor().getRouteType())
                        .reason("skill_route:" + skillDecision.getDescriptor().getSkillId())
                        .fromFallback(false)
                        .clarificationRequired(false)
                        .build();
            }
        }
        return AssistantExecutionPlan.builder()
                .runId(run.getRunId())
                .conversationId(run.getConversationId())
                .originalMessage(request.getMessage())
                .clientContext(request.getClientContext())
                .executionMode(clarificationRequired ? AssistantExecutionMode.CLARIFICATION : AssistantExecutionMode.SKILL)
                .routeDecision(decision)
                .skillDecision(skillDecision)
                .responseMessage(decision.getClarificationPrompt())
                .options(decision.getClarificationOptions())
                .reason(decision.getReason())
                .build();
    }

    private AssistantExecutionPlan lowConfidenceSkillPlan(AiRun run, AssistantRunCreateRequest request, AssistantRouteDecision decision, AssistantSkillDecision skillDecision) {
        String skillName = skillDecision.getDescriptor() == null ? "当前 Skill" : skillDecision.getDescriptor().getName();
        return AssistantExecutionPlan.builder()
                .runId(run.getRunId())
                .conversationId(run.getConversationId())
                .originalMessage(request.getMessage())
                .clientContext(request.getClientContext())
                .executionMode(AssistantExecutionMode.CLARIFICATION)
                .routeDecision(AssistantRouteDecision.builder()
                        .routeType(decision.getRouteType())
                        .reason("skill_low_confidence:" + skillDecision.getSkillId())
                        .fromFallback(false)
                        .clarificationRequired(true)
                        .clarificationPrompt("我还不能确定是否应该使用“" + skillName + "”。请补充你的具体目标。")
                        .clarificationOptions(List.of("查询或推荐演出", "查看节目详情或票档", "生成购票预览", "咨询退票、入场或购票规则"))
                        .build())
                .skillDecision(skillDecision)
                .responseMessage("我还不能确定是否应该使用“" + skillName + "”。请补充你的具体目标。")
                .options(List.of("查询或推荐演出", "查看节目详情或票档", "生成购票预览", "咨询退票、入场或购票规则"))
                .reason("skill_low_confidence:" + skillDecision.getSkillId())
                .build();
    }

    private AssistantExecutionPlan skillClarificationPlan(AiRun run, AssistantRunCreateRequest request, AssistantRouteDecision decision, AssistantSkillDecision skillDecision) {
        String prompt = switch (skillDecision.getReason()) {
            case "skill:forbidden" -> "当前账号无权访问该 Skill。你可以继续使用已开放的助手能力。";
            case "skill:disabled" -> "该 Skill 当前已停用，请选择其他助手能力。";
            case "skill:not_found" -> "没有找到请求的 Skill，请检查入口配置或改用自然语言描述目标。";
            default -> "当前没有可用的 Skill 处理该请求。";
        };
        return AssistantExecutionPlan.builder()
                .runId(run.getRunId())
                .conversationId(run.getConversationId())
                .originalMessage(request.getMessage())
                .clientContext(request.getClientContext())
                .executionMode(AssistantExecutionMode.CLARIFICATION)
                .routeDecision(decision)
                .skillDecision(skillDecision)
                .responseMessage(prompt)
                .options(List.of("帮我查询和推荐演出", "咨询退票、入场或购票规则", "进行通用问答"))
                .reason(skillDecision.getReason())
                .build();
    }

    private AssistantRouteDecision resolveRouteDecision(String message, Map<String, Object> clientContext) {
        Object hintValue = clientContext == null ? null : clientContext.get("routeHint");
        AssistantRouteType hintedRoute = AssistantRouteType.fromCode(hintValue == null ? null : String.valueOf(hintValue));
        if (hintedRoute != null) {
            return AssistantRouteDecision.builder()
                    .routeType(hintedRoute)
                    .reason("compat:" + hintedRoute.getCode())
                    .fromFallback(false)
                    .clarificationRequired(false)
                    .build();
        }
        return routeService.route(message);
    }

    // ---- Sentiment-aware routing ----

    private static final Set<String> NEGATIVE_KEYWORDS = Set.of(
            "投诉", "举报", "骗子", "欺诈", "太过分", "气死", "坑人", "退款不退",
            "垃圾", "差劲", "没人管", "不管", "不处理", "到底有没有人", "找客服",
            "转人工", "人工客服", "我要投诉", "我要退款", "什么破平台"
    );

    private SentimentCheckResult checkSentiment(String message) {
        if (message == null) return SentimentCheckResult.NEUTRAL;
        String lower = message.toLowerCase();
        boolean hit = NEGATIVE_KEYWORDS.stream().anyMatch(lower::contains);
        if (!hit) return SentimentCheckResult.NEUTRAL;
        return new SentimentCheckResult(true,
                "非常抱歉给您带来不好的体验。我理解您目前的心情，建议您转接人工客服获得更直接的帮助。");
    }

    private record SentimentCheckResult(boolean strongNegative, String responseMessage) {
        static final SentimentCheckResult NEUTRAL = new SentimentCheckResult(false, null);
    }
}
