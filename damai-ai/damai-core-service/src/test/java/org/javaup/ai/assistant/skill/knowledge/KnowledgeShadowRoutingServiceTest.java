package org.javaup.ai.assistant.skill.knowledge;

import org.javaup.ai.ai.rag.MarkdownLoader;
import org.javaup.ai.config.KnowledgeRoutingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeShadowRoutingServiceTest {

    @Test
    void shouldRankRelevantScopesAndDocuments() {
        MarkdownLoader markdownLoader = mock(MarkdownLoader.class);
        KnowledgeRoutingProperties properties = new KnowledgeRoutingProperties();
        when(markdownLoader.loadMarkdownsFlat()).thenReturn(List.of(
                new Document("问题：退票多久到账", Map.of(
                        "label", "退票退款",
                        "docTitle", "退票退款完整操作指南",
                        "question", "退票多久到账",
                        "keywords", "退票,退款,到账",
                        "sourceFile", "退票退款完整操作指南-长文档.md"
                )),
                new Document("问题：实名制入场需要什么", Map.of(
                        "label", "实名入场",
                        "docTitle", "实名与入场规则",
                        "question", "实名制入场需要什么证件",
                        "keywords", "实名,入场,证件",
                        "sourceFile", "实名与入场规则.md"
                ))
        ));
        KnowledgeShadowRoutingService service = new KnowledgeShadowRoutingService(markdownLoader, properties);

        KnowledgeShadowRouteResult result = service.shadowRoute("退票多久到账");

        assertEquals("shadow", result.mode());
        assertFalse(result.scopeCandidates().isEmpty());
        assertEquals("退票退款", result.scopeCandidates().get(0).name());
        assertEquals("退票退款完整操作指南-长文档.md", result.documentCandidates().get(0).name());
    }
}
