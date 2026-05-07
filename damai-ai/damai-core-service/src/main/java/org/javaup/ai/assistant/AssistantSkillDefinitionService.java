package org.javaup.ai.assistant;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.dto.AssistantSkillUpdateRequest;
import org.javaup.ai.entity.AiSkill;
import org.javaup.ai.entity.AiSkillChangeLog;
import org.javaup.ai.entity.AiSkillEvalCase;
import org.javaup.ai.entity.AiSkillEvalRun;
import org.javaup.ai.entity.AiSkillResource;
import org.javaup.ai.mapper.AiSkillChangeLogMapper;
import org.javaup.ai.mapper.AiSkillEvalCaseMapper;
import org.javaup.ai.mapper.AiSkillEvalRunMapper;
import org.javaup.ai.mapper.AiSkillMapper;
import org.javaup.ai.mapper.AiSkillResourceMapper;
import org.javaup.ai.vo.AssistantSkillEvalRunVo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AssistantSkillDefinitionService {

    private final AiSkillMapper skillMapper;
    private final AiSkillResourceMapper skillResourceMapper;
    private final AiSkillEvalCaseMapper evalCaseMapper;
    private final AiSkillEvalRunMapper evalRunMapper;
    private final AiSkillChangeLogMapper changeLogMapper;

    public AssistantSkillDescriptor mergeDescriptor(AssistantSkillDescriptor javaDescriptor) {
        AiSkill dbSkill = findSkill(javaDescriptor.getSkillId());
        if (dbSkill == null) {
            return javaDescriptor;
        }
        AssistantSkillDescriptor.AssistantSkillDescriptorBuilder builder = javaDescriptor.toBuilder();
        if (hasText(dbSkill.getName())) {
            builder.name(dbSkill.getName());
        }
        if (hasText(dbSkill.getDescription())) {
            builder.description(dbSkill.getDescription());
        }
        if (hasText(dbSkill.getVersion())) {
            builder.version(dbSkill.getVersion());
        }
        if (hasText(dbSkill.getGoal())) {
            builder.goal(dbSkill.getGoal());
        }
        if (hasText(dbSkill.getInstructions())) {
            builder.instructions(dbSkill.getInstructions());
        }
        if (hasText(dbSkill.getCategory())) {
            builder.category(dbSkill.getCategory());
        }
        if (hasText(dbSkill.getTriggerKeywordsJson())) {
            builder.triggerKeywords(parseStringList(dbSkill.getTriggerKeywordsJson()));
        }
        if (hasText(dbSkill.getToolAllowlistJson())) {
            builder.toolAllowlist(parseStringList(dbSkill.getToolAllowlistJson()));
        }
        if (hasText(dbSkill.getExamplesJson())) {
            builder.examples(parseStringList(dbSkill.getExamplesJson()));
        }
        if (hasText(dbSkill.getEvalCasesJson())) {
            builder.evalCases(parseStringList(dbSkill.getEvalCasesJson()));
        }
        if (hasText(dbSkill.getInputSchemaJson())) {
            builder.inputSchemaJson(dbSkill.getInputSchemaJson());
        }
        if (hasText(dbSkill.getOutputSchemaJson())) {
            builder.outputSchemaJson(dbSkill.getOutputSchemaJson());
        }
        if (hasText(dbSkill.getRiskLevel())) {
            builder.riskLevel(parseRisk(dbSkill.getRiskLevel(), javaDescriptor.getRiskLevel()));
        }
        if (dbSkill.getRequiresAdmin() != null) {
            builder.requiresAdmin(toBoolean(dbSkill.getRequiresAdmin()));
        }
        if (dbSkill.getRequiresApproval() != null) {
            builder.requiresApproval(toBoolean(dbSkill.getRequiresApproval()));
        }
        if (dbSkill.getEnabled() != null) {
            builder.enabled(toBoolean(dbSkill.getEnabled()));
        }
        if (dbSkill.getFrontendSelectable() != null) {
            builder.frontendSelectable(toBoolean(dbSkill.getFrontendSelectable()));
        }
        if (dbSkill.getModelSelectable() != null) {
            builder.modelSelectable(toBoolean(dbSkill.getModelSelectable()));
        }
        return builder.build();
    }

    public AssistantSkillResourceBundle loadResources(String skillId) {
        try {
            List<AiSkillResource> resources = skillResourceMapper.selectList(Wrappers.lambdaQuery(AiSkillResource.class)
                    .eq(AiSkillResource::getSkillId, skillId)
                    .eq(AiSkillResource::getStatus, 1)
                    .eq(AiSkillResource::getEnabled, 1)
                    .orderByAsc(AiSkillResource::getId));
            return AssistantSkillResourceBundle.builder()
                    .resources(resources.stream()
                            .map(resource -> AssistantSkillResourceItem.builder()
                                    .resourceId(resource.getResourceId())
                                    .resourceType(resource.getResourceType())
                                    .title(resource.getTitle())
                                    .content(resource.getContent())
                                    .metadataJson(resource.getMetadataJson())
                                    .build())
                            .toList())
                    .build();
        } catch (RuntimeException ex) {
            return AssistantSkillResourceBundle.empty();
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public AssistantSkillDescriptor saveDescriptor(AssistantSkillDescriptor defaultDescriptor, AssistantSkillUpdateRequest request, Long operatorUserId) {
        AiSkill before = findSkill(defaultDescriptor.getSkillId());
        AiSkill target = before == null ? toEntity(defaultDescriptor) : cloneSkill(before);
        applyUpdate(target, request);
        target.setStatus(1);
        if (before == null) {
            skillMapper.insert(target);
        } else {
            skillMapper.updateById(target);
        }
        AiSkill after = findSkill(defaultDescriptor.getSkillId());
        recordChange(defaultDescriptor.getSkillId(), operatorUserId, before == null ? "CREATE" : "UPDATE", before, after);
        return mergeDescriptor(defaultDescriptor);
    }

    @Transactional(rollbackFor = Exception.class)
    public AssistantSkillEvalRunVo createEvalRun(String skillId, Long userId, List<Map<String, Object>> caseResults, String errorMessage) {
        List<Map<String, Object>> results = caseResults == null ? List.of() : caseResults;
        AiSkillEvalRun run = new AiSkillEvalRun();
        run.setEvalRunId(nextId("skill_eval"));
        run.setSkillId(skillId);
        run.setUserId(userId);
        run.setRunStatus(evalRunStatus(results, errorMessage));
        run.setCaseCount(results.size());
        run.setPassedCount((int) results.stream()
                .filter(result -> Boolean.TRUE.equals(result.get("passed")))
                .count());
        run.setResultJson(JSON.toJSONString(results));
        run.setErrorMessage(errorMessage);
        run.setStatus(1);
        evalRunMapper.insert(run);
        return AssistantSkillEvalRunVo.builder()
                .evalRunId(run.getEvalRunId())
                .skillId(skillId)
                .runStatus(run.getRunStatus())
                .caseCount(run.getCaseCount())
                .passedCount(run.getPassedCount())
                .resultJson(run.getResultJson())
                .build();
    }

    private String evalRunStatus(List<Map<String, Object>> results, String errorMessage) {
        if (hasText(errorMessage)) {
            return "FAILED";
        }
        if (results == null || results.isEmpty()) {
            return "NO_CASES";
        }
        boolean allPassed = results.stream().allMatch(result -> Boolean.TRUE.equals(result.get("passed")));
        return allPassed ? "PASSED" : "FAILED";
    }

    private AiSkill findSkill(String skillId) {
        try {
            return skillMapper.selectOne(Wrappers.lambdaQuery(AiSkill.class)
                    .eq(AiSkill::getSkillId, skillId)
                    .eq(AiSkill::getStatus, 1)
                    .last("limit 1"));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    public List<AiSkillEvalCase> listEvalCases(String skillId) {
        try {
            return evalCaseMapper.selectList(Wrappers.lambdaQuery(AiSkillEvalCase.class)
                    .eq(AiSkillEvalCase::getSkillId, skillId)
                    .eq(AiSkillEvalCase::getStatus, 1)
                    .eq(AiSkillEvalCase::getEnabled, 1));
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private AiSkill toEntity(AssistantSkillDescriptor descriptor) {
        AiSkill skill = new AiSkill();
        skill.setSkillId(descriptor.getSkillId());
        skill.setName(descriptor.getName());
        skill.setDescription(descriptor.getDescription());
        skill.setVersion(descriptor.getVersion());
        skill.setGoal(descriptor.getGoal());
        skill.setInstructions(descriptor.getInstructions());
        skill.setRouteType(descriptor.getRouteType().getCode());
        skill.setCategory(descriptor.getCategory());
        skill.setTriggerKeywordsJson(JSON.toJSONString(nullToEmpty(descriptor.getTriggerKeywords())));
        skill.setToolAllowlistJson(JSON.toJSONString(nullToEmpty(descriptor.getToolAllowlist())));
        skill.setExamplesJson(JSON.toJSONString(nullToEmpty(descriptor.getExamples())));
        skill.setEvalCasesJson(JSON.toJSONString(nullToEmpty(descriptor.getEvalCases())));
        skill.setInputSchemaJson(descriptor.getInputSchemaJson());
        skill.setOutputSchemaJson(descriptor.getOutputSchemaJson());
        skill.setRiskLevel(descriptor.getRiskLevel() == null ? AssistantSkillRiskLevel.LOW.name() : descriptor.getRiskLevel().name());
        skill.setRequiresAdmin(toInt(descriptor.getRequiresAdmin()));
        skill.setRequiresApproval(toInt(descriptor.getRequiresApproval()));
        skill.setEnabled(toInt(descriptor.getEnabled()));
        skill.setExecutorType(descriptor.getExecutorType());
        skill.setFrontendSelectable(toInt(descriptor.getFrontendSelectable()));
        skill.setModelSelectable(toInt(descriptor.getModelSelectable()));
        skill.setPrimarySkill(toInt(descriptor.getPrimarySkill()));
        return skill;
    }

    private AiSkill cloneSkill(AiSkill source) {
        AiSkill target = new AiSkill();
        target.setId(source.getId());
        target.setSkillId(source.getSkillId());
        target.setName(source.getName());
        target.setDescription(source.getDescription());
        target.setVersion(source.getVersion());
        target.setGoal(source.getGoal());
        target.setInstructions(source.getInstructions());
        target.setRouteType(source.getRouteType());
        target.setCategory(source.getCategory());
        target.setTriggerKeywordsJson(source.getTriggerKeywordsJson());
        target.setToolAllowlistJson(source.getToolAllowlistJson());
        target.setExamplesJson(source.getExamplesJson());
        target.setEvalCasesJson(source.getEvalCasesJson());
        target.setInputSchemaJson(source.getInputSchemaJson());
        target.setOutputSchemaJson(source.getOutputSchemaJson());
        target.setRiskLevel(source.getRiskLevel());
        target.setRequiresAdmin(source.getRequiresAdmin());
        target.setRequiresApproval(source.getRequiresApproval());
        target.setEnabled(source.getEnabled());
        target.setExecutorType(source.getExecutorType());
        target.setFrontendSelectable(source.getFrontendSelectable());
        target.setModelSelectable(source.getModelSelectable());
        target.setPrimarySkill(source.getPrimarySkill());
        target.setStatus(source.getStatus());
        return target;
    }

    private void applyUpdate(AiSkill target, AssistantSkillUpdateRequest request) {
        if (request == null) {
            return;
        }
        if (request.getName() != null) {
            target.setName(request.getName());
        }
        if (request.getDescription() != null) {
            target.setDescription(request.getDescription());
        }
        if (request.getVersion() != null) {
            target.setVersion(request.getVersion());
        }
        if (request.getGoal() != null) {
            target.setGoal(request.getGoal());
        }
        if (request.getInstructions() != null) {
            target.setInstructions(request.getInstructions());
        }
        if (request.getCategory() != null) {
            target.setCategory(request.getCategory());
        }
        if (request.getTriggerKeywords() != null) {
            target.setTriggerKeywordsJson(JSON.toJSONString(nullToEmpty(request.getTriggerKeywords())));
        }
        if (request.getToolAllowlist() != null) {
            target.setToolAllowlistJson(JSON.toJSONString(nullToEmpty(request.getToolAllowlist())));
        }
        if (request.getExamples() != null) {
            target.setExamplesJson(JSON.toJSONString(nullToEmpty(request.getExamples())));
        }
        if (request.getEvalCases() != null) {
            target.setEvalCasesJson(JSON.toJSONString(nullToEmpty(request.getEvalCases())));
        }
        if (request.getInputSchemaJson() != null) {
            target.setInputSchemaJson(request.getInputSchemaJson());
        }
        if (request.getOutputSchemaJson() != null) {
            target.setOutputSchemaJson(request.getOutputSchemaJson());
        }
        if (request.getRiskLevel() != null) {
            target.setRiskLevel(parseRisk(request.getRiskLevel(), AssistantSkillRiskLevel.LOW).name());
        }
        if (request.getRequiresAdmin() != null) {
            target.setRequiresAdmin(toInt(request.getRequiresAdmin()));
        }
        if (request.getRequiresApproval() != null) {
            target.setRequiresApproval(toInt(request.getRequiresApproval()));
        }
        if (request.getEnabled() != null) {
            target.setEnabled(toInt(request.getEnabled()));
        }
        if (request.getFrontendSelectable() != null) {
            target.setFrontendSelectable(toInt(request.getFrontendSelectable()));
        }
        if (request.getModelSelectable() != null) {
            target.setModelSelectable(toInt(request.getModelSelectable()));
        }
    }

    private void recordChange(String skillId, Long operatorUserId, String changeType, AiSkill before, AiSkill after) {
        try {
            AiSkillChangeLog changeLog = new AiSkillChangeLog();
            changeLog.setChangeId(nextId("skill_change"));
            changeLog.setSkillId(skillId);
            changeLog.setOperatorUserId(operatorUserId);
            changeLog.setChangeType(changeType);
            changeLog.setBeforeJson(before == null ? null : JSON.toJSONString(before));
            changeLog.setAfterJson(after == null ? null : JSON.toJSONString(after));
            changeLog.setStatus(1);
            changeLogMapper.insert(changeLog);
        } catch (RuntimeException ignored) {
        }
    }

    private List<String> parseStringList(String raw) {
        try {
            return new ArrayList<>(JSON.parseArray(raw, String.class));
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private AssistantSkillRiskLevel parseRisk(String riskLevel, AssistantSkillRiskLevel fallback) {
        try {
            return AssistantSkillRiskLevel.valueOf(riskLevel.trim().toUpperCase());
        } catch (RuntimeException ex) {
            return fallback == null ? AssistantSkillRiskLevel.LOW : fallback;
        }
    }

    private List<String> nullToEmpty(List<String> values) {
        return values == null ? List.of() : values;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private Boolean toBoolean(Integer value) {
        return value != null && value == 1;
    }

    private Integer toInt(Boolean value) {
        return Boolean.TRUE.equals(value) ? 1 : 0;
    }

    private String nextId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }
}
