package org.javaup.ai.assistant;

import org.javaup.ai.dto.AssistantRunCreateRequest;
import org.javaup.ai.entity.AiRun;
import org.javaup.ai.context.AiUserContext;
import org.javaup.ai.security.AiPermissionService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantExecutionPlannerTest {

    @Test
    void shouldBuildClarificationPlanWhenRouteRequiresClarification() {
        AssistantRouteService routeService = mock(AssistantRouteService.class);
        when(routeService.route("看看这个")).thenReturn(AssistantRouteDecision.builder()
                .routeType(AssistantRouteType.BUSINESS)
                .reason("clarification:OTHER")
                .fromFallback(false)
                .clarificationRequired(true)
                .clarificationPrompt("请补充你的目标")
                .build());
        AiPermissionService permissionService = mock(AiPermissionService.class);
        when(permissionService.canAccessRoute(user(false), AssistantRouteType.BUSINESS)).thenReturn(true);
        AssistantExecutionPlanner planner = new AssistantExecutionPlanner(routeService, permissionService);

        AssistantExecutionPlan plan = planner.plan(run(), user(false), request("看看这个", null));

        assertEquals(AssistantExecutionMode.CLARIFICATION, plan.getExecutionMode());
        assertEquals("请补充你的目标", plan.getResponseMessage());
        assertEquals("clarification:OTHER", plan.getReason());
    }

    @Test
    void shouldRespectRouteHintWithoutCallingRouter() {
        AssistantRouteService routeService = mock(AssistantRouteService.class);
        AiPermissionService permissionService = mock(AiPermissionService.class);
        when(permissionService.canAccessRoute(user(true), AssistantRouteType.OPS)).thenReturn(true);
        AssistantExecutionPlanner planner = new AssistantExecutionPlanner(routeService, permissionService);

        AssistantExecutionPlan plan = planner.plan(run(), user(true), request("随便聊聊", Map.of("routeHint", "ops")));

        assertEquals(AssistantExecutionMode.SKILL, plan.getExecutionMode());
        assertEquals(AssistantRouteType.OPS, plan.getRouteDecision().getRouteType());
        assertEquals("compat:ops", plan.getRouteDecision().getReason());
        verify(routeService, never()).route("随便聊聊");
    }

    @Test
    void shouldRespectGeneralRouteHintWithoutCallingRouter() {
        AssistantRouteService routeService = mock(AssistantRouteService.class);
        AiPermissionService permissionService = mock(AiPermissionService.class);
        when(permissionService.canAccessRoute(user(false), AssistantRouteType.GENERAL)).thenReturn(true);
        AssistantExecutionPlanner planner = new AssistantExecutionPlanner(routeService, permissionService);

        AssistantExecutionPlan plan = planner.plan(run(), user(false), request("介绍一下这个歌手", Map.of("routeHint", "general")));

        assertEquals(AssistantExecutionMode.SKILL, plan.getExecutionMode());
        assertEquals(AssistantRouteType.GENERAL, plan.getRouteDecision().getRouteType());
        assertEquals("compat:general", plan.getRouteDecision().getReason());
        verify(routeService, never()).route("介绍一下这个歌手");
    }

    @Test
    void shouldBlockOpsRouteHintForNonAdminUser() {
        AssistantRouteService routeService = mock(AssistantRouteService.class);
        AiPermissionService permissionService = mock(AiPermissionService.class);
        when(permissionService.canAccessRoute(user(false), AssistantRouteType.OPS)).thenReturn(false);
        AssistantExecutionPlanner planner = new AssistantExecutionPlanner(routeService, permissionService);

        AssistantExecutionPlan plan = planner.plan(run(), user(false), request("查一下 gateway cpu", Map.of("routeHint", "ops")));

        assertEquals(AssistantExecutionMode.CLARIFICATION, plan.getExecutionMode());
        assertEquals(AssistantRouteType.BUSINESS, plan.getRouteDecision().getRouteType());
        assertEquals("forbidden:ops", plan.getRouteDecision().getReason());
        verify(routeService, never()).route("查一下 gateway cpu");
    }

    private AiRun run() {
        AiRun run = new AiRun();
        run.setRunId("run_1");
        run.setConversationId("chat_1");
        return run;
    }

    private AssistantRunCreateRequest request(String message, Map<String, Object> clientContext) {
        AssistantRunCreateRequest request = new AssistantRunCreateRequest();
        request.setMessage(message);
        request.setClientContext(clientContext);
        return request;
    }

    private AiUserContext user(boolean admin) {
        return AiUserContext.builder().userId(admin ? 1L : 2L).admin(admin).build();
    }
}
