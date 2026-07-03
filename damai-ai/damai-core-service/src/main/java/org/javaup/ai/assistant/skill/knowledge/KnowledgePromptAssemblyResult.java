package org.javaup.ai.assistant.skill.knowledge;

import org.javaup.ai.runtime.contract.SourceRef;

import java.util.List;
import java.util.Map;

public record KnowledgePromptAssemblyResult(
        String contextBlock,
        String groundedPrompt,
        int contextCharBudget,
        int renderedDocumentCount,
        Map<String, SourceRef> sourceRefs
) {
    public List<SourceRef> sourceRefList() {
        return sourceRefs == null ? List.of()
                : sourceRefs.entrySet().stream()
                        .sorted((a, b) -> a.getKey().compareTo(b.getKey()))
                        .map(Map.Entry::getValue)
                        .toList();
    }
}
