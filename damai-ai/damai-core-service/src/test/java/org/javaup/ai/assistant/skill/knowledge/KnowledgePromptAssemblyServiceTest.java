package org.javaup.ai.assistant.skill.knowledge;

import org.javaup.ai.vo.RagSearchResultVo;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgePromptAssemblyServiceTest {

    private final KnowledgePromptAssemblyService service = new KnowledgePromptAssemblyService();

    @Test
    void shouldAssembleGroundedPromptWithinContextBudget() {
        KnowledgeRetrievalPlan plan = new KnowledgeRetrievalPlan("退票规则", "退票规则", 8, true, 6, 260, 12, List.of("退票规则"), KnowledgeRetrievalPlan.Complexity.MEDIUM);
        KnowledgeRetrievalContext context = new KnowledgeRetrievalContext(
                plan,
                RagSearchResultVo.builder().build(),
                new StructuredRuleSupportService.SupportBundle(List.of(), List.of()),
                new KnowledgeRetrievalAssessment(0.8D, "CORRECT", "none", List.of(),
                        "HIGH", "HIGH", false, "ANSWERABLE", ""),
                List.of(document("1234567890abcdef"), document("duplicate"), document("duplicate"))
        );

        KnowledgePromptAssemblyResult result = service.assemble("可以退票吗", context);

        assertEquals(12, result.contextCharBudget());
        assertTrue(result.contextBlock().length() <= 12);
        assertEquals(1, result.renderedDocumentCount());
        assertTrue(result.groundedPrompt().contains("只能基于给定证据回答"));
        assertTrue(result.groundedPrompt().contains("可以退票吗"));
    }

    private Document document(String text) {
        return new Document(text, Map.of("chunkId", text));
    }
}
