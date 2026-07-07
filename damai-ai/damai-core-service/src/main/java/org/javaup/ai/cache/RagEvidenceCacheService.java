package org.javaup.ai.cache;

import com.alibaba.fastjson2.JSON;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.javaup.ai.config.CacheProperties;
import org.javaup.ai.rag.RetrievalStrategy;
import org.javaup.ai.rag.RetrievalStrategyProfile;
import org.javaup.ai.rag.channel.KnowledgeRetrievalFilter;
import org.javaup.ai.vo.RagSearchResultVo;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@Service
public class RagEvidenceCacheService {

    private static final String UNKNOWN_VERSION = "unknown";

    private final CacheManager cacheManager;
    private final CacheProperties properties;
    private final CacheMetrics metrics;
    private final EmbeddingCacheService embeddingCacheService;
    private final OpenAiEmbeddingModel embeddingModel;
    private final Cache<String, SemanticEntry> semanticIndex;

    public RagEvidenceCacheService(CacheManager cacheManager,
                                   CacheProperties properties,
                                   CacheMetrics metrics,
                                   EmbeddingCacheService embeddingCacheService,
                                   OpenAiEmbeddingModel embeddingModel) {
        this.cacheManager = cacheManager;
        this.properties = properties;
        this.metrics = metrics;
        this.embeddingCacheService = embeddingCacheService;
        this.embeddingModel = embeddingModel;
        this.semanticIndex = Caffeine.newBuilder()
                .maximumSize(Math.max(1, Math.min(properties.getRagEvidence().getMaxSize(),
                        properties.getRagEvidence().getSemanticMaxSize())))
                .expireAfterWrite(Math.max(1, properties.getRagEvidence().getTtlMinutes()), TimeUnit.MINUTES)
                .build();
    }

    public RagEvidenceCacheKey buildKey(String originalQuery,
                                        String rewrittenQuery,
                                        RetrievalStrategy strategy,
                                        KnowledgeRetrievalFilter filter,
                                        String knowledgeVersion) {
        RetrievalStrategy effectiveStrategy = strategy == null
                ? RetrievalStrategy.standardHybrid(5, true, false, "default cache strategy")
                : strategy;
        KnowledgeRetrievalFilter effectiveFilter = filter == null ? KnowledgeRetrievalFilter.empty() : filter;
        String normalizedOriginal = normalize(originalQuery);
        String normalizedRewritten = normalize(StringUtils.hasText(rewrittenQuery) ? rewrittenQuery : originalQuery);
        String filterHash = sha256(JSON.toJSONString(filterFingerprint(effectiveFilter)));
        String configHash = sha256(JSON.toJSONString(strategyFingerprint(effectiveStrategy)));
        String version = StringUtils.hasText(knowledgeVersion) ? knowledgeVersion : UNKNOWN_VERSION;
        String namespace = sha256(String.join("|",
                "rag-evidence",
                version,
                effectiveStrategy.profile().name(),
                configHash,
                filterHash,
                nullToGlobal(effectiveFilter.getUserScope())));
        String exactKey = String.join("|",
                namespace,
                "topK:" + effectiveStrategy.topK(),
                "query:" + sha256(normalizedOriginal),
                "rewritten:" + sha256(normalizedRewritten));
        return new RagEvidenceCacheKey(exactKey, namespace, normalizedRewritten, version, filterHash, configHash,
                nullToGlobal(effectiveFilter.getUserScope()), cacheable(effectiveStrategy));
    }

    public CacheLookup get(RagEvidenceCacheKey key) {
        if (key == null || !properties.getRagEvidence().isEnabled()) {
            return CacheLookup.miss();
        }
        String exact = cacheManager.getRagEvidence(key.exactKey());
        if (StringUtils.hasText(exact)) {
            RagSearchResultVo result = parse(exact);
            if (result != null) {
                return new CacheLookup(result, "exact", key.exactKey(), 1.0D);
            }
        }
        CacheLookup semantic = semanticLookup(key);
        if (semantic.hit()) {
            metrics.recordRagEvidenceSemantic(true);
        } else if (properties.getRagEvidence().isSemanticEnabled()) {
            metrics.recordRagEvidenceSemantic(false);
        }
        return semantic;
    }

    public void put(RagEvidenceCacheKey key, RagSearchResultVo result) {
        if (key == null || result == null || !key.cacheable() || !properties.getRagEvidence().isEnabled()) {
            return;
        }
        if (result.getSources() == null || result.getSources().isEmpty()) {
            return;
        }
        RagSearchResultVo evidence = withoutDocuments(result);
        cacheManager.putRagEvidence(key.exactKey(), JSON.toJSONString(evidence));
        indexSemantic(key);
    }

    public void invalidate() {
        semanticIndex.invalidateAll();
        cacheManager.invalidateRagEvidence();
    }

    public RagSearchResultVo withCacheMetadata(RagSearchResultVo result,
                                               RagEvidenceCacheKey key,
                                               boolean hit,
                                               String hitType,
                                               Double similarity) {
        if (result == null) {
            return null;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (result.getMetadata() != null) {
            metadata.putAll(result.getMetadata());
        }
        metadata.put("evidenceCacheHit", hit);
        metadata.put("evidenceCacheHitType", hitType == null ? "miss" : hitType);
        metadata.put("evidenceCacheKey", key == null ? "" : key.exactKey());
        metadata.put("knowledgeVersion", key == null ? UNKNOWN_VERSION : key.knowledgeVersion());
        if (similarity != null) {
            metadata.put("evidenceCacheSimilarity", similarity);
        }
        return rebuildWithMetadata(result, metadata);
    }

    private CacheLookup semanticLookup(RagEvidenceCacheKey key) {
        if (!properties.getRagEvidence().isSemanticEnabled() || !key.cacheable()) {
            return CacheLookup.miss();
        }
        float[] queryVector = vectorFor(key.semanticQuery());
        if (queryVector == null || queryVector.length == 0) {
            return CacheLookup.miss();
        }
        SemanticEntry best = null;
        double bestScore = -1D;
        long now = System.currentTimeMillis();
        for (SemanticEntry candidate : semanticIndex.asMap().values()) {
            if (candidate.expiresAt() <= now) {
                semanticIndex.invalidate(candidate.exactKey());
                continue;
            }
            if (!Objects.equals(candidate.namespace(), key.semanticNamespace())) {
                continue;
            }
            double score = cosine(queryVector, candidate.vector());
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        if (best == null || bestScore < properties.getRagEvidence().getSemanticThreshold()) {
            return CacheLookup.miss();
        }
        String cached = cacheManager.getRagEvidence(best.exactKey());
        RagSearchResultVo result = StringUtils.hasText(cached) ? parse(cached) : null;
        return result == null ? CacheLookup.miss() : new CacheLookup(result, "semantic", best.exactKey(), bestScore);
    }

    private void indexSemantic(RagEvidenceCacheKey key) {
        if (!properties.getRagEvidence().isSemanticEnabled() || !key.cacheable()) {
            return;
        }
        float[] vector = vectorFor(key.semanticQuery());
        if (vector == null || vector.length == 0) {
            return;
        }
        long expiresAt = System.currentTimeMillis()
                + Duration.ofMinutes(Math.max(1, properties.getRagEvidence().getTtlMinutes())).toMillis();
        semanticIndex.put(key.exactKey(), new SemanticEntry(key.exactKey(), key.semanticNamespace(), key.semanticQuery(), vector, expiresAt));
    }

    private float[] vectorFor(String text) {
        if (!StringUtils.hasText(text) || embeddingCacheService == null) {
            return null;
        }
        float[] vector = embeddingCacheService.get(text);
        if (vector != null) {
            return vector;
        }
        if (embeddingModel == null) {
            return null;
        }
        vector = embeddingModel.embed(text);
        if (vector != null && vector.length > 0) {
            embeddingCacheService.put(text, vector);
        }
        return vector;
    }

    private RagSearchResultVo parse(String json) {
        try {
            return JSON.parseObject(json, RagSearchResultVo.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private RagSearchResultVo withoutDocuments(RagSearchResultVo result) {
        return RagSearchResultVo.builder()
                .originalQuery(result.getOriginalQuery())
                .normalizedQuery(result.getNormalizedQuery())
                .rewrittenQuery(result.getRewrittenQuery())
                .retrievalTraceId(result.getRetrievalTraceId())
                .documents(List.of())
                .sources(result.getSources())
                .denseSources(result.getDenseSources())
                .sparseSources(result.getSparseSources())
                .fusedSources(result.getFusedSources())
                .confidenceScore(result.getConfidenceScore())
                .confidenceLevel(result.getConfidenceLevel())
                .correctiveAction(result.getCorrectiveAction())
                .metadata(result.getMetadata())
                .build();
    }

    private RagSearchResultVo rebuildWithMetadata(RagSearchResultVo result, Map<String, Object> metadata) {
        return RagSearchResultVo.builder()
                .originalQuery(result.getOriginalQuery())
                .normalizedQuery(result.getNormalizedQuery())
                .rewrittenQuery(result.getRewrittenQuery())
                .retrievalTraceId(result.getRetrievalTraceId())
                .documents(result.getDocuments())
                .sources(result.getSources())
                .denseSources(result.getDenseSources())
                .sparseSources(result.getSparseSources())
                .fusedSources(result.getFusedSources())
                .confidenceScore(result.getConfidenceScore())
                .confidenceLevel(result.getConfidenceLevel())
                .correctiveAction(result.getCorrectiveAction())
                .metadata(metadata)
                .build();
    }

    private boolean cacheable(RetrievalStrategy strategy) {
        return strategy.profile() != RetrievalStrategyProfile.HANDOFF_OR_CLARIFY && !strategy.highRisk();
    }

    private Map<String, Object> filterFingerprint(KnowledgeRetrievalFilter filter) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("scope", nullToEmpty(filter.getScope()));
        value.put("topic", nullToEmpty(filter.getTopic()));
        value.put("documentIds", filter.normalizedDocumentIds());
        value.put("audience", nullToEmpty(filter.getAudience()));
        value.put("region", nullToEmpty(filter.getRegion()));
        value.put("channel", nullToEmpty(filter.getChannel()));
        value.put("validAt", filter.getValidAt() == null ? 0L : filter.getValidAt());
        value.put("userScope", nullToGlobal(filter.getUserScope()));
        return value;
    }

    private Map<String, Object> strategyFingerprint(RetrievalStrategy strategy) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("profile", strategy.profile().name());
        value.put("topK", strategy.topK());
        value.put("enableDense", strategy.enableDense());
        value.put("enableSparse", strategy.enableSparse());
        value.put("enableHyde", strategy.enableHyde());
        value.put("enableRerank", strategy.enableRerank());
        value.put("enableQueryRewrite", strategy.enableQueryRewrite());
        value.put("enableEntityExpansion", strategy.enableEntityExpansion());
        value.put("enableSentenceWindow", strategy.enableSentenceWindow());
        value.put("enableParentElevation", strategy.enableParentElevation());
        value.put("enableCorrectiveRetrieval", strategy.enableCorrectiveRetrieval());
        value.put("enabledChannels", strategy.enabledChannels());
        return value;
    }

    private double cosine(float[] a, float[] b) {
        int length = Math.min(a.length, b.length);
        if (length == 0) {
            return 0D;
        }
        double dot = 0D;
        double left = 0D;
        double right = 0D;
        for (int i = 0; i < length; i++) {
            dot += a[i] * b[i];
            left += a[i] * a[i];
            right += b[i] * b[i];
        }
        if (left == 0D || right == 0D) {
            return 0D;
        }
        return dot / (Math.sqrt(left) * Math.sqrt(right));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase();
    }

    private String nullToEmpty(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }

    private String nullToGlobal(String value) {
        return StringUtils.hasText(value) ? value.trim() : "global";
    }

    private static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public record RagEvidenceCacheKey(String exactKey,
                                      String semanticNamespace,
                                      String semanticQuery,
                                      String knowledgeVersion,
                                      String filterHash,
                                      String configHash,
                                      String userScope,
                                      boolean cacheable) {
    }

    public record CacheLookup(RagSearchResultVo result, String hitType, String matchedKey, Double similarity) {
        public static CacheLookup miss() {
            return new CacheLookup(null, "miss", "", null);
        }

        public boolean hit() {
            return result != null;
        }
    }

    private record SemanticEntry(String exactKey,
                                 String namespace,
                                 String query,
                                 float[] vector,
                                 long expiresAt) {
    }
}
