package org.javaup.ai.assistant;

import org.javaup.ai.context.AiUserContext;
import org.javaup.ai.dto.AssistantRunCreateRequest;
import org.javaup.ai.security.AiPermissionService;
import org.javaup.ai.service.LlmSkillSelectorService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class AssistantSkillSelector {

    private final AssistantSkillRegistry skillRegistry;
    private final AiPermissionService aiPermissionService;
    private final AssistantSkillDefinitionService skillDefinitionService;
    private final LlmSkillSelectorService llmSkillSelectorService;

    @Autowired
    public AssistantSkillSelector(AssistantSkillRegistry skillRegistry,
                                  AiPermissionService aiPermissionService,
                                  ObjectProvider<AssistantSkillDefinitionService> skillDefinitionServiceProvider,
                                  ObjectProvider<LlmSkillSelectorService> llmSkillSelectorServiceProvider) {
        this.skillRegistry = skillRegistry;
        this.aiPermissionService = aiPermissionService;
        this.skillDefinitionService = skillDefinitionServiceProvider.getIfAvailable();
        this.llmSkillSelectorService = llmSkillSelectorServiceProvider.getIfAvailable();
    }

    public AssistantSkillDecision select(AssistantRouteType routeType, AiUserContext user, AssistantRunCreateRequest request) {
        String hintedSkillId = hintValue(request == null ? null : request.getClientContext(), "skillHint");
        if (hintedSkillId != null) {
            AssistantSkillDescriptor hinted = fresh(skillRegistry.getDescriptor(hintedSkillId));
            if (hinted == null) {
                return decision(hintedSkillId, null, "skill:not_found", 0.0, true);
            }
            if (!hinted.enabled()) {
                return decision(hintedSkillId, hinted, "skill:disabled", 0.0, true);
            }
            if (!aiPermissionService.canAccessSkill(user, hinted)) {
                return decision(hintedSkillId, hinted, "skill:forbidden", 0.0, true);
            }
            return decision(hinted.getSkillId(), hinted, "skill_hint:" + hinted.getSkillId(), 1.0, true);
        }

        String message = request == null ? "" : request.getMessage();
        List<AssistantSkillDescriptor> candidates = skillRegistry.listDescriptors(routeType).stream()
                .map(this::fresh)
                .filter(AssistantSkillDescriptor::enabled)
                .filter(descriptor -> !Boolean.FALSE.equals(descriptor.getModelSelectable()))
                .filter(descriptor -> aiPermissionService.canAccessSkill(user, descriptor))
                .toList();
        if (candidates.isEmpty()) {
            return decision(null, null, "skill:no_available_candidate", 0.0, false);
        }
        int bestKeywordScore = candidates.stream()
                .mapToInt(descriptor -> score(message, descriptor))
                .max().orElse(0);

        AssistantSkillDescriptor matched;
        double confidence;
        String reason;

        if (bestKeywordScore > 0) {
            matched = candidates.stream()
                    .map(descriptor -> new ScoredSkill(descriptor, score(message, descriptor)))
                    .max(Comparator.comparingInt(ScoredSkill::score)
                            .thenComparing(scored -> scored.descriptor().primarySkill() ? 1 : 0))
                    .map(ScoredSkill::descriptor)
                    .orElseGet(() -> primaryOrFirst(candidates));
            confidence = 0.82;
            reason = "skill_candidate:" + matched.getSkillId();
        } else if (llmSkillSelectorService != null && candidates.size() > 1) {
            String llmSelectedId = llmSkillSelectorService.selectSkillId(message, candidates);
            if (llmSelectedId != null) {
                matched = candidates.stream()
                        .filter(c -> c.getSkillId().equals(llmSelectedId))
                        .findFirst()
                        .orElseGet(() -> primaryOrFirst(candidates));
                confidence = 0.70;
                reason = "skill_llm:" + matched.getSkillId();
            } else {
                matched = primaryOrFirst(candidates);
                confidence = 0.55;
                reason = "skill_primary:" + matched.getSkillId();
            }
        } else {
            matched = primaryOrFirst(candidates);
            confidence = 0.55;
            reason = "skill_primary:" + matched.getSkillId();
        }
        return decision(matched.getSkillId(), matched, reason, confidence, false);
    }

    private AssistantSkillDescriptor primaryOrFirst(List<AssistantSkillDescriptor> candidates) {
        return candidates.stream()
                .filter(AssistantSkillDescriptor::primarySkill)
                .findFirst()
                .orElse(candidates.get(0));
    }

    private int score(String message, AssistantSkillDescriptor descriptor) {
        if (message == null || descriptor.getTriggerKeywords() == null) {
            return 0;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String keyword : descriptor.getTriggerKeywords()) {
            if (keyword != null && !keyword.isBlank() && normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                score += 3;
            }
        }
        score += textScore(normalized, descriptor.getName());
        score += textScore(normalized, descriptor.getDescription());
        score += textScore(normalized, descriptor.getGoal());
        if (descriptor.getExamples() != null) {
            for (String example : descriptor.getExamples()) {
                score += textScore(normalized, example);
            }
        }
        return score;
    }

    private int textScore(String message, String candidateText) {
        if (candidateText == null || candidateText.isBlank()) {
            return 0;
        }
        int score = 0;
        String normalizedText = candidateText.toLowerCase(Locale.ROOT);
        for (String token : normalizedText.split("[,，。；;、\\s]+")) {
            if (token.length() >= 2 && message.contains(token)) {
                score++;
            }
        }
        return score;
    }

    private AssistantSkillDecision decision(String skillId, AssistantSkillDescriptor descriptor, String reason, Double confidence, boolean fromHint) {
        return AssistantSkillDecision.builder()
                .skillId(skillId)
                .descriptor(descriptor)
                .reason(reason)
                .confidence(confidence)
                .fromHint(fromHint)
                .build();
    }

    private AssistantSkillDescriptor fresh(AssistantSkillDescriptor descriptor) {
        if (descriptor == null || skillDefinitionService == null) {
            return descriptor;
        }
        return skillDefinitionService.mergeDescriptor(descriptor);
    }

    private String hintValue(Map<String, Object> clientContext, String key) {
        Object raw = clientContext == null ? null : clientContext.get(key);
        if (raw == null) {
            return null;
        }
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? null : value;
    }

    private record ScoredSkill(AssistantSkillDescriptor descriptor, int score) {
    }
}
