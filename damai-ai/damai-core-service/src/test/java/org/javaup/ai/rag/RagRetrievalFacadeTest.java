package org.javaup.ai.rag;

import org.javaup.ai.rag.channel.SearchContext;
import org.javaup.ai.rag.engine.MultiChannelRetrievalEngine;
import org.javaup.ai.service.AdvancedQueryService;
import org.javaup.ai.service.RagSearchBackendService;
import org.javaup.ai.vo.RagSearchResultVo;
import org.javaup.ai.vo.RagSourceVo;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagRetrievalFacadeTest {

    @Test
    void shouldResolveDocumentsAndAttachBoundaryMetadataForFullRetrieval() {
        MultiChannelRetrievalEngine engine = mock(MultiChannelRetrievalEngine.class);
        AdvancedQueryService advancedQueryService = mock(AdvancedQueryService.class);
        RagSearchBackendService searchBackendService = mock(RagSearchBackendService.class);
        RagRetrievalFacade facade = new RagRetrievalFacade(engine, advancedQueryService, searchBackendService);
        RagSourceVo source = source("chunk-1");
        Document document = new Document("退票规则正文", Map.of("chunkId", "chunk-1"));

        when(advancedQueryService.rewriteQuery("退票")).thenReturn(
                new AdvancedQueryService.QueryRewriteResult("退票规则", List.of("退票规则")));
        when(engine.retrieve(any(SearchContext.class))).thenReturn(RagSearchResultVo.builder()
                .originalQuery("退票")
                .normalizedQuery("退票")
                .rewrittenQuery("退票规则")
                .denseSources(List.of(source))
                .sparseSources(List.of(source))
                .fusedSources(List.of(source))
                .sources(List.of(source))
                .documents(List.of())
                .build());
        when(searchBackendService.resolveDocuments(List.of(source))).thenReturn(List.of(document));

        RagSearchResultVo result = facade.retrieve("退票", 5, true);

        assertEquals(1, result.getDocuments().size());
        assertEquals("RagRetrievalFacade", result.getMetadata().get("retrievalBoundary"));
        assertEquals("full", result.getMetadata().get("retrievalMode"));
        assertEquals("MultiChannelRetrievalEngine", result.getMetadata().get("retrievalEngine"));
        assertEquals("RagSearchBackendService.resolveDocuments", result.getMetadata().get("documentResolver"));
        assertEquals(true, result.getMetadata().get("documentResolvedByFacade"));
        assertEquals(1, result.getMetadata().get("finalHitCount"));
        verify(engine).retrieve(any(SearchContext.class));
    }

    @Test
    void shouldKeepSimpleRetrievalBehindSameFacadeBoundary() {
        MultiChannelRetrievalEngine engine = mock(MultiChannelRetrievalEngine.class);
        AdvancedQueryService advancedQueryService = mock(AdvancedQueryService.class);
        RagSearchBackendService searchBackendService = mock(RagSearchBackendService.class);
        RagRetrievalFacade facade = new RagRetrievalFacade(engine, advancedQueryService, searchBackendService);
        RagSourceVo source = source("chunk-2");

        when(engine.retrieveSimple(any(SearchContext.class))).thenReturn(RagSearchResultVo.builder()
                .originalQuery("实名")
                .rewrittenQuery("实名")
                .sources(List.of(source))
                .documents(List.of(new Document("实名规则", Map.of("chunkId", "chunk-2"))))
                .build());

        RagSearchResultVo result = facade.retrieveSimple("实名", 4);

        assertEquals("simple", result.getMetadata().get("retrievalMode"));
        assertEquals(false, result.getMetadata().get("enableRerank"));
        assertEquals(false, result.getMetadata().get("documentResolvedByFacade"));
        assertTrue(result.getDocuments().get(0).getText().contains("实名"));
        verify(engine).retrieveSimple(any(SearchContext.class));
    }

    private RagSourceVo source(String chunkId) {
        return RagSourceVo.builder()
                .chunkId(chunkId)
                .title("title")
                .source("faq")
                .section("section")
                .snippet("snippet")
                .score(0.9D)
                .build();
    }
}
