package org.javaup.ai.cache;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.javaup.ai.config.CacheProperties;
import org.javaup.ai.rag.RetrievalStrategy;
import org.javaup.ai.rag.channel.KnowledgeRetrievalFilter;
import org.javaup.ai.vo.RagSearchResultVo;
import org.javaup.ai.vo.RagSourceVo;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagEvidenceCacheServiceTest {

    private final Map<String, String> redis = new HashMap<>();
    private final CacheManager cacheManager = mock(CacheManager.class);
    private final EmbeddingCacheService embeddingCacheService = mock(EmbeddingCacheService.class);
    private final RagEvidenceCacheService service = cacheService();

    @Test
    void shouldReuseExactEvidenceWithoutPersistingDocuments() {
        RagEvidenceCacheService.RagEvidenceCacheKey key = key("退票规则", "admin:1", "v1");
        RagSearchResultVo result = result("chunk-1");

        service.put(key, result);
        RagEvidenceCacheService.CacheLookup lookup = service.get(key);

        assertTrue(lookup.hit());
        assertEquals("exact", lookup.hitType());
        assertEquals("chunk-1", lookup.result().getSources().get(0).getChunkId());
        assertTrue(lookup.result().getDocuments().isEmpty());
    }

    @Test
    void shouldHitSemanticEvidenceWithinSameScopeVersionAndConfig() {
        when(embeddingCacheService.get("退票规则")).thenReturn(new float[]{1F, 0F});
        when(embeddingCacheService.get("退票怎么办")).thenReturn(new float[]{0.99F, 0.01F});
        RagEvidenceCacheService.RagEvidenceCacheKey sourceKey = key("退票规则", "admin:1", "v1");
        RagEvidenceCacheService.RagEvidenceCacheKey similarKey = key("退票怎么办", "admin:1", "v1");

        service.put(sourceKey, result("chunk-refund"));
        RagEvidenceCacheService.CacheLookup lookup = service.get(similarKey);

        assertTrue(lookup.hit());
        assertEquals("semantic", lookup.hitType());
        assertEquals("chunk-refund", lookup.result().getSources().get(0).getChunkId());
        assertTrue(lookup.similarity() >= 0.93D);
    }

    @Test
    void shouldNotShareSemanticEvidenceAcrossUserScopeOrKnowledgeVersion() {
        when(embeddingCacheService.get(anyString())).thenReturn(new float[]{1F, 0F});
        RagEvidenceCacheService.RagEvidenceCacheKey sourceKey = key("退票规则", "admin:1", "v1");
        RagEvidenceCacheService.RagEvidenceCacheKey otherUser = key("退票怎么办", "admin:2", "v1");
        RagEvidenceCacheService.RagEvidenceCacheKey otherVersion = key("退票怎么办", "admin:1", "v2");

        service.put(sourceKey, result("chunk-refund"));

        assertFalse(service.get(otherUser).hit());
        assertFalse(service.get(otherVersion).hit());
        assertNotEquals(sourceKey.semanticNamespace(), otherUser.semanticNamespace());
        assertNotEquals(sourceKey.semanticNamespace(), otherVersion.semanticNamespace());
    }

    private RagEvidenceCacheService cacheService() {
        when(cacheManager.getRagEvidence(anyString())).thenAnswer(invocation -> redis.get(invocation.getArgument(0)));
        doAnswer(invocation -> {
            redis.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(cacheManager).putRagEvidence(anyString(), anyString());
        CacheProperties properties = new CacheProperties();
        properties.getRagEvidence().setSemanticThreshold(0.93D);
        return new RagEvidenceCacheService(cacheManager, properties,
                new CacheMetrics(new SimpleMeterRegistry()), embeddingCacheService, null);
    }

    private RagEvidenceCacheService.RagEvidenceCacheKey key(String query, String userScope, String version) {
        return service.buildKey(query, query,
                RetrievalStrategy.standardHybrid(5, true, false, "test"),
                KnowledgeRetrievalFilter.builder().userScope(userScope).build(),
                version);
    }

    private RagSearchResultVo result(String chunkId) {
        return RagSearchResultVo.builder()
                .originalQuery("退票")
                .rewrittenQuery("退票规则")
                .documents(List.of(new org.springframework.ai.document.Document("不应进入 evidence cache")))
                .sources(List.of(RagSourceVo.builder()
                        .chunkId(chunkId)
                        .title("退票")
                        .source("faq")
                        .snippet("退票规则")
                        .score(0.9D)
                        .build()))
                .metadata(Map.of("profile", "candidate"))
                .build();
    }
}
