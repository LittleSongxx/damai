package org.javaup.ai.assistant;

import org.javaup.ai.context.AiUserContext;
import org.javaup.ai.entity.AiSkillEvalCase;
import org.javaup.ai.security.AiPermissionService;
import org.javaup.ai.vo.AssistantSkillEvalRunVo;
import org.javaup.ai.assistant.tool.AssistantSkillToolRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AssistantSkillManagementServiceTest {

    @Test
    void shouldExecuteEvalCasesAndPersistSnapshots() {
        AssistantSkillRegistry skillRegistry = mock(AssistantSkillRegistry.class);
        AssistantSkillDefinitionService definitionService = mock(AssistantSkillDefinitionService.class);
        AiPermissionService permissionService = mock(AiPermissionService.class);
        AssistantSkillManagementService service = new AssistantSkillManagementService(
                skillRegistry,
                definitionService,
                permissionService,
                new AssistantSkillPolicyGuard(permissionService),
                new AssistantSkillSchemaValidator(),
                new AssistantSkillToolRegistry()
        );
        AiUserContext user = AiUserContext.builder().userId(1L).admin(true).build();
        AssistantSkillDescriptor descriptor = descriptor();
        AssistantSkill skill = mock(AssistantSkill.class);
        AiSkillEvalCase evalCase = evalCase();
        when(permissionService.isAdmin(user)).thenReturn(true);
        when(permissionService.canAccessSkill(user, descriptor)).thenReturn(true);
        when(skillRegistry.getDescriptor("business.program.search")).thenReturn(descriptor);
        when(skillRegistry.getRequired("business.program.search")).thenReturn(skill);
        when(definitionService.mergeDescriptor(descriptor)).thenReturn(descriptor);
        when(definitionService.loadResources("business.program.search")).thenReturn(AssistantSkillResourceBundle.empty());
        when(definitionService.listEvalCases("business.program.search")).thenReturn(List.of(evalCase));
        when(skill.execute(any())).thenReturn(AssistantSkillResult.builder()
                .message("命中 2 个节目")
                .responseSummary("命中 2 个节目")
                .build());
        when(definitionService.createEvalRun(eq("business.program.search"), eq(1L), any(), isNull())).thenReturn(AssistantSkillEvalRunVo.builder()
                .evalRunId("skill_eval_1")
                .skillId("business.program.search")
                .runStatus("PASSED")
                .caseCount(1)
                .passedCount(1)
                .resultJson("[]")
                .build());

        AssistantSkillEvalRunVo vo = service.createEvalRun("business.program.search", user);

        assertEquals("PASSED", vo.getRunStatus());
        verify(skill).execute(any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> resultsCaptor = ArgumentCaptor.forClass(List.class);
        verify(definitionService).createEvalRun(eq("business.program.search"), eq(1L), resultsCaptor.capture(), isNull());
        List<Map<String, Object>> results = resultsCaptor.getValue();
        assertEquals(1, results.size());
        assertEquals("case_1", results.get(0).get("caseId"));
        assertEquals(true, results.get(0).get("passed"));
        assertTrue(results.get(0).containsKey("output"));
    }

    private AssistantSkillDescriptor descriptor() {
        return AssistantSkillDescriptor.builder()
                .skillId("business.program.search")
                .name("节目搜索")
                .description("搜索节目")
                .version("1.0.0")
                .routeType(AssistantRouteType.BUSINESS)
                .category("business")
                .toolAllowlist(List.of())
                .outputSchemaJson("{\"required\":[\"message\",\"responseSummary\"]}")
                .riskLevel(AssistantSkillRiskLevel.LOW)
                .requiresAdmin(false)
                .requiresApproval(false)
                .enabled(true)
                .executorType("java")
                .frontendSelectable(true)
                .modelSelectable(true)
                .primarySkill(false)
                .build();
    }

    private AiSkillEvalCase evalCase() {
        AiSkillEvalCase evalCase = new AiSkillEvalCase();
        evalCase.setCaseId("case_1");
        evalCase.setSkillId("business.program.search");
        evalCase.setQuestion("找演唱会");
        evalCase.setExpectedOutputJson("{\"contains\":\"命中\"}");
        evalCase.setEnabled(1);
        return evalCase;
    }
}
