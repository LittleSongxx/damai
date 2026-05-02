package org.javaup.ai.assistant.skill.knowledge;

public record KnowledgePromptAssemblyResult(
        String contextBlock,
        String groundedPrompt,
        int contextCharBudget,
        int renderedDocumentCount
) {
}
