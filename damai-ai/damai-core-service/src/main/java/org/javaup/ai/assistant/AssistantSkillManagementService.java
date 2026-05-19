package org.javaup.ai.assistant;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.assistant.memory.AssistantMemoryContext;
import org.javaup.ai.assistant.profile.AssistantUserProfileContext;
import org.javaup.ai.assistant.tool.AssistantSkillToolRegistry;
import org.javaup.ai.assistant.tool.AssistantSkillToolScope;
import org.javaup.ai.context.AiUserContext;
import org.javaup.ai.dto.AssistantSkillUpdateRequest;
import org.javaup.ai.dto.AssistantRunCreateRequest;
import org.javaup.ai.entity.AiRun;
import org.javaup.ai.entity.AiSkillEvalCase;
import org.javaup.ai.security.AiAuthorizationException;
import org.javaup.ai.security.AiPermissionService;
import org.javaup.ai.vo.AssistantSkillDetailVo;
import org.javaup.ai.vo.AssistantSkillEvalRunVo;
import org.javaup.ai.vo.AssistantSkillResourceVo;
import org.javaup.ai.vo.AssistantSkillVo;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AssistantSkillManagementService {

    private final AssistantSkillRegistry skillRegistry;
    private final AssistantSkillDefinitionService definitionService;
    private final AiPermissionService permissionService;
    private final AssistantSkillPolicyGuard policyGuard;
    private final AssistantSkillSchemaValidator schemaValidator;
    private final AssistantSkillToolRegistry toolRegistry;

    public List<AssistantSkillVo> listSkills(AiUserContext user) {
        boolean admin = permissionService.isAdmin(user);
        return skillRegistry.listDescriptors().stream()
                .map(definitionService::mergeDescriptor)
                .filter(descriptor -> admin || visibleToUser(user, descriptor))
                .map(this::toVo)
                .toList();
    }

    public List<AssistantSkillVo> listCapabilities(AiUserContext user) {
        return skillRegistry.listDescriptors().stream()
                .map(definitionService::mergeDescriptor)
                .filter(descriptor -> visibleToUser(user, descriptor))
                .filter(descriptor -> !Boolean.FALSE.equals(descriptor.getFrontendSelectable()))
                .map(this::toVo)
                .toList();
    }

    public AssistantSkillDetailVo getSkill(String skillId, AiUserContext user) {
        AssistantSkillDescriptor descriptor = requireDescriptor(skillId);
        descriptor = definitionService.mergeDescriptor(descriptor);
        if (!permissionService.isAdmin(user) && !visibleToUser(user, descriptor)) {
            throw new AiAuthorizationException("当前账号无权访问该 Skill");
        }
        return AssistantSkillDetailVo.builder()
                .skill(toVo(descriptor))
                .resources(definitionService.loadResources(skillId).getResources().stream()
                        .map(resource -> AssistantSkillResourceVo.builder()
                                .resourceId(resource.getResourceId())
                                .resourceType(resource.getResourceType())
                                .title(resource.getTitle())
                                .content(resource.getContent())
                                .metadataJson(resource.getMetadataJson())
                                .build())
                        .toList())
                .build();
    }

    public AssistantSkillVo updateSkill(String skillId, AssistantSkillUpdateRequest request, AiUserContext user) {
        requireAdmin(user);
        AssistantSkillDescriptor defaultDescriptor = requireDescriptor(skillId);
        return toVo(definitionService.saveDescriptor(defaultDescriptor, request, user.getUserId()));
    }

    public AssistantSkillEvalRunVo createEvalRun(String skillId, AiUserContext user) {
        requireAdmin(user);
        AssistantSkillDescriptor descriptor = definitionService.mergeDescriptor(requireDescriptor(skillId));
        AssistantSkill skill = skillRegistry.getRequired(skillId);
        AssistantSkillResourceBundle resources = definitionService.loadResources(skillId);
        List<AiSkillEvalCase> cases = definitionService.listEvalCases(skillId);
        List<Map<String, Object>> results = new ArrayList<>();
        String errorMessage = null;
        try {
            policyGuard.verifyBeforeExecution(user, descriptor);
            for (AiSkillEvalCase evalCase : cases) {
                results.add(executeEvalCase(skill, descriptor, resources, evalCase, user));
            }
        } catch (RuntimeException ex) {
            errorMessage = ex.getMessage();
        }
        return definitionService.createEvalRun(skillId, user.getUserId(), results, errorMessage);
    }

    private AssistantSkillDescriptor requireDescriptor(String skillId) {
        AssistantSkillDescriptor descriptor = skillRegistry.getDescriptor(skillId);
        if (descriptor == null) {
            throw new IllegalArgumentException("skill not found: " + skillId);
        }
        return descriptor;
    }

    private boolean visibleToUser(AiUserContext user, AssistantSkillDescriptor descriptor) {
        return descriptor.enabled() && permissionService.canAccessSkill(user, descriptor);
    }

    private void requireAdmin(AiUserContext user) {
        if (!permissionService.isAdmin(user)) {
            throw new AiAuthorizationException("当前账号无权管理 Skill");
        }
    }

    private AssistantSkillVo toVo(AssistantSkillDescriptor descriptor) {
        return AssistantSkillVo.builder()
                .skillId(descriptor.getSkillId())
                .name(descriptor.getName())
                .description(descriptor.getDescription())
                .version(descriptor.getVersion())
                .goal(descriptor.getGoal())
                .instructions(descriptor.getInstructions())
                .routeType(descriptor.getRouteType() == null ? null : descriptor.getRouteType().getCode())
                .category(descriptor.getCategory())
                .triggerKeywords(descriptor.getTriggerKeywords())
                .toolAllowlist(descriptor.getToolAllowlist())
                .examples(descriptor.getExamples())
                .evalCases(descriptor.getEvalCases())
                .inputSchemaJson(descriptor.getInputSchemaJson())
                .outputSchemaJson(descriptor.getOutputSchemaJson())
                .riskLevel(descriptor.getRiskLevel() == null ? null : descriptor.getRiskLevel().name())
                .requiresAdmin(Boolean.TRUE.equals(descriptor.getRequiresAdmin()))
                .requiresApproval(Boolean.TRUE.equals(descriptor.getRequiresApproval()))
                .enabled(descriptor.enabled())
                .executorType(descriptor.getExecutorType())
                .frontendSelectable(!Boolean.FALSE.equals(descriptor.getFrontendSelectable()))
                .modelSelectable(!Boolean.FALSE.equals(descriptor.getModelSelectable()))
                .primarySkill(Boolean.TRUE.equals(descriptor.getPrimarySkill()))
                .build();
    }

    private Map<String, Object> executeEvalCase(AssistantSkill skill,
                                                AssistantSkillDescriptor descriptor,
                                                AssistantSkillResourceBundle resources,
                                                AiSkillEvalCase evalCase,
                                                AiUserContext user) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("caseId", evalCase.getCaseId());
        result.put("question", evalCase.getQuestion());
        result.put("expectedOutputJson", evalCase.getExpectedOutputJson());
        try {
            AssistantSkillContext context = evalContext(descriptor, resources, evalCase, user);
            schemaValidator.validateInput(descriptor, context);
            AssistantSkillResult skillResult;
            try (AssistantSkillToolScope.ScopeHandle ignored = toolRegistry.openScope(descriptor)) {
                skillResult = skill.execute(context);
            }
            schemaValidator.validateOutput(descriptor, skillResult);
            policyGuard.verifyAfterExecution(descriptor, skillResult);
            List<String> failures = new ArrayList<>();
            boolean passed = matchesExpected(evalCase.getExpectedOutputJson(), skillResult, failures);
            result.put("passed", passed);
            result.put("failures", failures);
            result.put("output", outputSnapshot(skillResult));
        } catch (RuntimeException ex) {
            result.put("passed", false);
            result.put("error", ex.getMessage());
        }
        return result;
    }

    private AssistantSkillContext evalContext(AssistantSkillDescriptor descriptor,
                                              AssistantSkillResourceBundle resources,
                                              AiSkillEvalCase evalCase,
                                              AiUserContext user) {
        AiRun run = new AiRun();
        run.setRunId("skill_eval_case_" + evalCase.getCaseId());
        run.setConversationId("skill_eval_" + descriptor.getSkillId());
        run.setUserId(user.getUserId());
        run.setRouteType(descriptor.getRouteType() == null ? null : descriptor.getRouteType().getCode());
        run.setSkillId(descriptor.getSkillId());
        run.setSkillVersion(descriptor.getVersion());
        run.setRunStatus(AssistantRunStatus.RUNNING.name());
        AssistantRunCreateRequest request = new AssistantRunCreateRequest();
        request.setMessage(evalCase.getQuestion());
        request.setClientContext(Map.of(
                "eval", true,
                "caseId", evalCase.getCaseId(),
                "skillId", descriptor.getSkillId()
        ));
        return AssistantSkillContext.of(run, user, request, AssistantMemoryContext.empty(), AssistantUserProfileContext.empty(), descriptor, resources);
    }

    private Map<String, Object> outputSnapshot(AssistantSkillResult result) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("message", result == null ? null : result.getMessage());
        output.put("responseSummary", result == null ? null : result.getResponseSummary());
        output.put("hasPendingAction", result != null && result.getPendingAction() != null);
        output.put("pendingActionId", result == null || result.getPendingAction() == null ? null : result.getPendingAction().getActionId());
        output.put("hasRetrieval", result != null && result.getRetrieval() != null);
        return output;
    }

    private boolean matchesExpected(String expectedOutputJson, AssistantSkillResult result, List<String> failures) {
        String outputText = ((result == null || result.getMessage() == null ? "" : result.getMessage()) + "\n"
                + (result == null || result.getResponseSummary() == null ? "" : result.getResponseSummary()));
        if (expectedOutputJson == null || expectedOutputJson.isBlank()) {
            boolean hasOutput = !outputText.isBlank();
            if (!hasOutput) {
                failures.add("输出为空");
            }
            return hasOutput;
        }
        try {
            Object expected = JSON.parse(expectedOutputJson);
            if (expected instanceof JSONObject object) {
                return matchesExpectedObject(object, outputText, result, failures);
            }
            if (expected instanceof JSONArray array) {
                return containsAll(array, outputText, failures);
            }
            return containsText(String.valueOf(expected), outputText, failures);
        } catch (RuntimeException ex) {
            return containsText(expectedOutputJson, outputText, failures);
        }
    }

    private boolean matchesExpectedObject(JSONObject expected, String outputText, AssistantSkillResult result, List<String> failures) {
        boolean passed = true;
        if (expected.containsKey("contains")) {
            passed = containsExpected(expected.get("contains"), outputText, failures) && passed;
        }
        if (expected.containsKey("notContains")) {
            passed = excludesExpected(expected.get("notContains"), outputText, failures) && passed;
        }
        if (expected.containsKey("requiresPendingAction")) {
            boolean expectedAction = expected.getBooleanValue("requiresPendingAction");
            boolean actualAction = result != null && result.getPendingAction() != null;
            if (expectedAction != actualAction) {
                failures.add("pendingAction 期望为 " + expectedAction + "，实际为 " + actualAction);
                passed = false;
            }
        }
        if (!expected.containsKey("contains") && !expected.containsKey("notContains") && !expected.containsKey("requiresPendingAction")) {
            passed = containsText(expected.toJSONString(), outputText, failures);
        }
        return passed;
    }

    private boolean containsExpected(Object expected, String outputText, List<String> failures) {
        if (expected instanceof JSONArray array) {
            return containsAll(array, outputText, failures);
        }
        return containsText(String.valueOf(expected), outputText, failures);
    }

    private boolean excludesExpected(Object expected, String outputText, List<String> failures) {
        if (expected instanceof JSONArray array) {
            boolean passed = true;
            for (Object item : array) {
                passed = excludesText(String.valueOf(item), outputText, failures) && passed;
            }
            return passed;
        }
        return excludesText(String.valueOf(expected), outputText, failures);
    }

    private boolean containsAll(JSONArray expectedItems, String outputText, List<String> failures) {
        boolean passed = true;
        for (Object item : expectedItems) {
            passed = containsText(String.valueOf(item), outputText, failures) && passed;
        }
        return passed;
    }

    private boolean containsText(String expectedText, String outputText, List<String> failures) {
        if (expectedText == null || expectedText.isBlank()) {
            return true;
        }
        if (!outputText.contains(expectedText)) {
            failures.add("输出未包含: " + expectedText);
            return false;
        }
        return true;
    }

    private boolean excludesText(String expectedText, String outputText, List<String> failures) {
        if (expectedText == null || expectedText.isBlank()) {
            return true;
        }
        if (outputText.contains(expectedText)) {
            failures.add("输出不应包含: " + expectedText);
            return false;
        }
        return true;
    }
}
