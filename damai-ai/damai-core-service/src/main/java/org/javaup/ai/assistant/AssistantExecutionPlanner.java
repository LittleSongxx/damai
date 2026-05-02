package org.javaup.ai.assistant;

import lombok.RequiredArgsConstructor;
import org.javaup.ai.context.AiUserContext;
import org.javaup.ai.dto.AssistantRunCreateRequest;
import org.javaup.ai.entity.AiRun;
import org.javaup.ai.security.AiPermissionService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AssistantExecutionPlanner {

    private final AssistantRouteService routeService;
    private final AiPermissionService aiPermissionService;

    public AssistantExecutionPlan plan(AiRun run, AiUserContext user, AssistantRunCreateRequest request) {
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
        boolean clarificationRequired = Boolean.TRUE.equals(decision.getClarificationRequired());
        return AssistantExecutionPlan.builder()
                .runId(run.getRunId())
                .conversationId(run.getConversationId())
                .originalMessage(request.getMessage())
                .clientContext(request.getClientContext())
                .executionMode(clarificationRequired ? AssistantExecutionMode.CLARIFICATION : AssistantExecutionMode.SKILL)
                .routeDecision(decision)
                .responseMessage(decision.getClarificationPrompt())
                .options(decision.getClarificationOptions())
                .reason(decision.getReason())
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
}
