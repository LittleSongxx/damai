package org.javaup.ai.rag;

import org.javaup.ai.cache.RagEvidenceCacheService;
import org.javaup.ai.rag.channel.SearchContext;
import org.javaup.ai.rag.engine.MultiChannelRetrievalEngine;
import org.javaup.ai.service.AdvancedQueryService;
import org.javaup.ai.service.KnowledgeIndexVersionService;
import org.javaup.ai.service.RagSearchBackendService;
import org.javaup.ai.service.SentenceWindowService;
import org.javaup.ai.vo.RagSearchResultVo;
import org.javaup.ai.vo.RagSourceVo;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagRetrievalFacadeTest {

    @Test
    void shouldResolveDocumentsAndAttachBoundaryMetadataForFullRetrieval() {
        MultiChannelRetrievalEngine engine = mock(MultiChannelRetrievalEngine.class);
        AdvancedQueryService advancedQueryService = mock(AdvancedQueryService.class);
        RagSearchBackendService searchBackendService = mock(RagSearchBackendService.class);
        SentenceWindowService sentenceWindowService = mock(SentenceWindowService.class);
        RagEvidenceCacheService cacheService = mock(RagEvidenceCacheService.class);
        KnowledgeIndexVersionService versionService = mock(KnowledgeIndexVersionService.class);
        RagRetrievalFacade facade = new RagRetrievalFacade(engine, advancedQueryService, searchBackendService,
                sentenceWindowService, cacheService, versionService);
        RagSourceVo source = source("chunk-1");
        Document document = new Document("退票规则正文", Map.of("chunkId", "chunk-1"));
        RagEvidenceCacheService.RagEvidenceCacheKey cacheKey = cacheKey("退票规则");

        when(advancedQueryService.rewriteQuery("退票")).thenReturn(
                new AdvancedQueryService.QueryRewriteResult("退票规则", List.of("退票规则")));
        when(versionService.currentVersion()).thenReturn("v-test");
        when(cacheService.buildKey(any(), any(), any(), any(), any())).thenReturn(cacheKey);
        when(cacheService.get(cacheKey)).thenReturn(RagEvidenceCacheService.CacheLookup.miss());
        when(cacheService.withCacheMetadata(any(), any(), anyBoolean(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
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
        when(sentenceWindowService.expand(any())).thenAnswer(invocation -> invocation.getArgument(0));

        RagSearchResultVo result = facade.retrieve("退票",
                RetrievalStrategy.standardHybrid(5, true, false, "test standard hybrid"),
                org.javaup.ai.rag.channel.KnowledgeRetrievalFilter.empty());

        assertEquals(1, result.getDocuments().size());
        assertEquals("RagRetrievalFacade", result.getMetadata().get("retrievalBoundary"));
        assertEquals("STANDARD_HYBRID", result.getMetadata().get("retrievalMode"));
        assertEquals("MultiChannelRetrievalEngine", result.getMetadata().get("retrievalEngine"));
        assertEquals("RagSearchBackendService.resolveDocuments", result.getMetadata().get("documentResolver"));
        assertEquals(true, result.getMetadata().get("documentResolvedByFacade"));
        assertEquals(1, result.getMetadata().get("finalHitCount"));
        verify(engine).retrieve(any(SearchContext.class));
        verify(cacheService).put(any(), any(RagSearchResultVo.class));
    }

    @Test
    void shouldKeepFastExactRetrievalBehindSameFacadeBoundary() {
        MultiChannelRetrievalEngine engine = mock(MultiChannelRetrievalEngine.class);
        AdvancedQueryService advancedQueryService = mock(AdvancedQueryService.class);
        RagSearchBackendService searchBackendService = mock(RagSearchBackendService.class);
        SentenceWindowService sentenceWindowService = mock(SentenceWindowService.class);
        RagEvidenceCacheService cacheService = mock(RagEvidenceCacheService.class);
        KnowledgeIndexVersionService versionService = mock(KnowledgeIndexVersionService.class);
        RagRetrievalFacade facade = new RagRetrievalFacade(engine, advancedQueryService, searchBackendService,
                sentenceWindowService, cacheService, versionService);
        RagSourceVo source = source("chunk-2");
        RagEvidenceCacheService.RagEvidenceCacheKey cacheKey = cacheKey("实名");

        when(versionService.currentVersion()).thenReturn("v-test");
        when(cacheService.buildKey(any(), any(), any(), any(), any())).thenReturn(cacheKey);
        when(cacheService.get(cacheKey)).thenReturn(RagEvidenceCacheService.CacheLookup.miss());
        when(cacheService.withCacheMetadata(any(), any(), anyBoolean(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(engine.retrieve(any(SearchContext.class))).thenReturn(RagSearchResultVo.builder()
                .originalQuery("实名")
                .rewrittenQuery("实名")
                .sources(List.of(source))
                .documents(List.of(new Document("实名规则", Map.of("chunkId", "chunk-2"))))
                .build());

        RagSearchResultVo result = facade.retrieve("实名",
                RetrievalStrategy.fastExact(4, "test fast exact"),
                org.javaup.ai.rag.channel.KnowledgeRetrievalFilter.empty());

        assertEquals("FAST_EXACT", result.getMetadata().get("retrievalMode"));
        assertEquals(false, result.getMetadata().get("enableRerank"));
        assertEquals(false, result.getMetadata().get("documentResolvedByFacade"));
        assertTrue(result.getDocuments().get(0).getText().contains("实名"));
        verify(engine).retrieve(any(SearchContext.class));
    }

    @Test
    void shouldResolveDocumentsFromCachedEvidenceWithoutCallingEngine() {
        MultiChannelRetrievalEngine engine = mock(MultiChannelRetrievalEngine.class);
        AdvancedQueryService advancedQueryService = mock(AdvancedQueryService.class);
        RagSearchBackendService searchBackendService = mock(RagSearchBackendService.class);
        SentenceWindowService sentenceWindowService = mock(SentenceWindowService.class);
        RagEvidenceCacheService cacheService = mock(RagEvidenceCacheService.class);
        KnowledgeIndexVersionService versionService = mock(KnowledgeIndexVersionService.class);
        RagRetrievalFacade facade = new RagRetrievalFacade(engine, advancedQueryService, searchBackendService,
                sentenceWindowService, cacheService, versionService);
        RagSourceVo source = source("chunk-cache");
        RagSearchResultVo cachedResult = RagSearchResultVo.builder()
                .originalQuery("退票")
                .rewrittenQuery("退票规则")
                .sources(List.of(source))
                .documents(List.of())
                .metadata(Map.of("evidenceCacheHit", true))
                .build();
        RagEvidenceCacheService.RagEvidenceCacheKey cacheKey = cacheKey("退票规则");

        when(advancedQueryService.rewriteQuery("退票")).thenReturn(
                new AdvancedQueryService.QueryRewriteResult("退票规则", List.of("退票规则")));
        when(versionService.currentVersion()).thenReturn("v-test");
        when(cacheService.buildKey(any(), any(), any(), any(), any())).thenReturn(cacheKey);
        when(cacheService.get(cacheKey)).thenReturn(new RagEvidenceCacheService.CacheLookup(cachedResult, "exact", cacheKey.exactKey(), 1.0D));
        when(cacheService.withCacheMetadata(any(), any(), anyBoolean(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(searchBackendService.resolveDocuments(List.of(source)))
                .thenReturn(List.of(new Document("缓存证据正文", Map.of("chunkId", "chunk-cache"))));
        when(sentenceWindowService.expand(any())).thenAnswer(invocation -> invocation.getArgument(0));

        RagSearchResultVo result = facade.retrieve("退票",
                RetrievalStrategy.standardHybrid(5, true, false, "cache hit"),
                org.javaup.ai.rag.channel.KnowledgeRetrievalFilter.empty());

        assertEquals(1, result.getDocuments().size());
        assertEquals(1, result.getMetadata().get("finalHitCount"));
        verify(engine, never()).retrieve(any(SearchContext.class));
        verify(cacheService, never()).put(any(), any());
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

    private RagEvidenceCacheService.RagEvidenceCacheKey cacheKey(String query) {
        return new RagEvidenceCacheService.RagEvidenceCacheKey(
                "exact-" + query,
                "namespace",
                query,
                "v-test",
                "filter",
                "config",
                "global",
                true);
    }
}
