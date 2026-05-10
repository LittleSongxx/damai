package org.javaup.ai.assistant.executor;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.assistant.AssistantEventTypes;
import org.javaup.ai.assistant.AssistantRunService;
import org.javaup.ai.assistant.AssistantSkill;
import org.javaup.ai.assistant.AssistantSkillContext;
import org.javaup.ai.assistant.AssistantSkillRegistry;
import org.javaup.ai.assistant.AssistantSkillResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MultiAgentCompositionService {

    private final AssistantSkillRegistry skillRegistry;
    private final AssistantRunService runService;
    private final AssistantMessageEmitter messageEmitter;

    public String executeSequence(String runId, String chatId, List<String> skillIds, AssistantSkillContext baseContext) {
        List<String> results = new ArrayList<>();
        for (int i = 0; i < skillIds.size(); i++) {
            String skillId = skillIds.get(i);
            try {
                AssistantSkill skill = skillRegistry.getRequired(skillId);
                AssistantSkillResult result = skill.execute(baseContext);

                String stepResult = result.getResponseSummary() != null ? result.getResponseSummary() : "completed";
                results.add(String.format("[%s] %s", skillId, stepResult));

                runService.appendEvent(runId, AssistantEventTypes.AGENT_STEP, Map.of(
                        "compositionStep", i + 1,
                        "skillId", skillId,
                        "result", stepResult
                ));
            } catch (Exception e) {
                log.warn("Multi-agent composition step failed: skillId={}, error={}", skillId, e.getMessage());
                results.add(String.format("[%s] ERROR: %s", skillId, e.getMessage()));
            }
        }

        String composedResult = String.join("\n", results);
        messageEmitter.emitMessage(runId, chatId, composedResult);
        return composedResult;
    }
}
