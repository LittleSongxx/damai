package org.javaup.ai.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.VectorsFactory;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.grpc.Points;
import io.qdrant.client.grpc.Points.PointStruct;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.cache.EmbeddingCacheService;
import org.javaup.ai.entity.FaqEntry;
import org.javaup.ai.mapper.FaqEntryMapper;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * FAQ精确匹配服务 - 参考 Dify hit_testing 设计。
 * 先关键词精确匹配，再embedding语义匹配（阈值>0.92），命中直接返回答案，
 * 未命中返回null让调用方fallback到完整RAG管线。
 */
@Slf4j
@Service
public class FaqMatchService {

    private final FaqEntryMapper faqEntryMapper;
    private final OpenAiEmbeddingModel embeddingModel;
    private final QdrantClient qdrantClient;
    private final EmbeddingCacheService embeddingCacheService;

    @Value("${damai.ai.faq.alias:damai-ai-faq-current}")
    private String faqAlias;

    @Value("${damai.ai.faq.match-threshold:0.92}")
    private double faqMatchThreshold;

    public FaqMatchService(FaqEntryMapper faqEntryMapper,
                           OpenAiEmbeddingModel embeddingModel,
                           QdrantClient qdrantClient,
                           EmbeddingCacheService embeddingCacheService) {
        this.faqEntryMapper = faqEntryMapper;
        this.embeddingModel = embeddingModel;
        this.qdrantClient = qdrantClient;
        this.embeddingCacheService = embeddingCacheService;
    }

    /**
     * 尝试FAQ精确匹配。命中返回FaqMatchResult，未命中返回null。
     */
    public FaqMatchResult match(String query) {
        if (!StringUtils.hasText(query)) {
            return null;
        }

        // 第一层：关键词精确匹配（毫秒级）
        FaqMatchResult keywordResult = keywordMatch(query);
        if (keywordResult != null) {
            log.debug("FAQ keyword match: faqId={}", keywordResult.faqId());
            return keywordResult;
        }

        // 第二层：Embedding语义匹配（高阈值）
        FaqMatchResult semanticResult = semanticMatch(query);
        if (semanticResult != null) {
            log.debug("FAQ semantic match: faqId={}", semanticResult.faqId());
            return semanticResult;
        }

        return null;
    }

    /**
     * 将FAQ条目向量化并写入Qdrant，使语义匹配层生效。
     * 对标准问题和相似问法分别生成embedding并upsert。
     */
    public void indexFaq(FaqEntry entry) {
        if (entry.getQuestion() == null || entry.getQuestion().isBlank()) {
            return;
        }
        List<String> textsToIndex = new ArrayList<>();
        textsToIndex.add(entry.getQuestion());
        if (StringUtils.hasText(entry.getSimilarQuestionsJson())) {
            try {
                List<String> similarQuestions = JSON.parseArray(entry.getSimilarQuestionsJson(), String.class);
                if (similarQuestions != null) {
                    textsToIndex.addAll(similarQuestions);
                }
            } catch (Exception ignored) {
            }
        }

        List<PointStruct> points = new ArrayList<>();
        for (int i = 0; i < textsToIndex.size(); i++) {
            String text = textsToIndex.get(i);
            if (!StringUtils.hasText(text)) continue;
            try {
                float[] vector = embeddingModel.embed(text);
                List<Float> vectorList = new ArrayList<>(vector.length);
                for (float v : vector) vectorList.add(v);

                String pointId = entry.getFaqId() + "_" + i;
                Map<String, io.qdrant.client.grpc.JsonWithInt.Value> payload = new HashMap<>();
                payload.put("faqId", ValueFactory.value(entry.getFaqId()));
                payload.put("question", ValueFactory.value(entry.getQuestion()));
                payload.put("text", ValueFactory.value(text));

                points.add(PointStruct.newBuilder()
                        .setId(PointIdFactory.id(pointId.hashCode() & 0xFFFFFFFFL))
                        .setVectors(VectorsFactory.vectors(vectorList))
                        .putAllPayload(payload)
                        .build());
            } catch (Exception e) {
                log.warn("Failed to embed FAQ text for faqId={}: {}", entry.getFaqId(), e.getMessage());
            }
        }

        if (!points.isEmpty()) {
            try {
                qdrantClient.upsertAsync(faqAlias, points).get();
                log.info("FAQ indexed to Qdrant: faqId={}, points={}", entry.getFaqId(), points.size());
            } catch (Exception e) {
                log.error("Failed to upsert FAQ to Qdrant: faqId={}", entry.getFaqId(), e);
            }
        }
    }

    /**
     * 从Qdrant中删除FAQ的所有向量点。
     */
    public void deleteFaqIndex(String faqId) {
        try {
            qdrantClient.deleteAsync(
                    faqAlias,
                    Points.Filter.newBuilder()
                            .addMust(Points.Condition.newBuilder()
                                    .setField(Points.FieldCondition.newBuilder()
                                            .setKey("faqId")
                                            .setMatch(Points.Match.newBuilder()
                                                    .setKeyword(faqId)
                                                    .build())
                                            .build())
                                    .build())
                            .build()
            ).get();
            log.info("FAQ deleted from Qdrant: faqId={}", faqId);
        } catch (Exception e) {
            log.warn("Failed to delete FAQ from Qdrant: faqId={}", faqId, e.getMessage());
        }
    }

    /**
     * 关键词精确匹配：基于用户问题中的关键词在FAQ条目中搜索。
     * 参考 Dify dataset_retrieval 中的keyword search方式。
     */
    private FaqMatchResult keywordMatch(String query) {
        List<FaqEntry> candidates = faqEntryMapper.selectList(
                Wrappers.lambdaQuery(FaqEntry.class)
                        .eq(FaqEntry::getEnabled, 1)
                        .orderByDesc(FaqEntry::getPriority));
        if (candidates.isEmpty()) {
            return null;
        }

        String normalizedQuery = normalize(query);
        FaqEntry bestMatch = null;
        int bestScore = 0;

        for (FaqEntry entry : candidates) {
            int score = computeKeywordMatchScore(normalizedQuery, entry);
            if (score > bestScore) {
                bestScore = score;
                bestMatch = entry;
            }
        }

        // 关键词精确匹配需要较高匹配度（至少3个关键词命中或完整匹配）
        if (bestMatch != null && bestScore >= 3) {
            incrementHitCount(bestMatch);
            return FaqMatchResult.from(bestMatch, "keyword", (double) bestScore / 10.0);
        }

        return null;
    }

    /**
     * 语义匹配：embedding + Qdrant向量检索。
     * 参考 Dify 的 semantic_search + score_threshold 设计。
     */
    private FaqMatchResult semanticMatch(String query) {
        try {
            float[] vector = embeddingCacheService.get(query);
            if (vector == null) {
                vector = embeddingModel.embed(query);
                embeddingCacheService.put(query, vector);
            }

            List<Float> vectorList = new ArrayList<>(vector.length);
            for (float v : vector) vectorList.add(v);

            List<Points.ScoredPoint> scoredPoints = qdrantClient.searchAsync(
                    Points.SearchPoints.newBuilder()
                            .setCollectionName(faqAlias)
                            .addAllVector(vectorList)
                            .setLimit(3)
                            .setScoreThreshold((float) faqMatchThreshold)
                            .setWithPayload(io.qdrant.client.WithPayloadSelectorFactory.enable(true))
                            .build()
            ).get();

            if (scoredPoints.isEmpty()) {
                return null;
            }

            Points.ScoredPoint topHit = scoredPoints.get(0);
            String faqId = topHit.getPayloadMap().get("faqId").getStringValue();

            FaqEntry entry = faqEntryMapper.selectOne(
                    Wrappers.lambdaQuery(FaqEntry.class)
                            .eq(FaqEntry::getFaqId, faqId)
                            .eq(FaqEntry::getEnabled, 1));

            if (entry == null) {
                return null;
            }

            incrementHitCount(entry);
            return FaqMatchResult.from(entry, "semantic", (double) topHit.getScore());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("FAQ semantic match interrupted");
        } catch (ExecutionException e) {
            log.warn("FAQ semantic match failed: {}", e.getMessage());
        }

        return null;
    }

    private int computeKeywordMatchScore(String normalizedQuery, FaqEntry entry) {
        int score = 0;

        // 检查标准问题匹配
        if (entry.getQuestion() != null && normalize(entry.getQuestion()).contains(normalizedQuery)) {
            score += 5;
        }
        if (normalizedQuery.contains(normalize(entry.getQuestion()))) {
            score += 4;
        }

        // 检查相似问法匹配 (参考 Dify 的 similar_questions 设计)
        if (StringUtils.hasText(entry.getSimilarQuestionsJson())) {
            try {
                List<String> similarQuestions = JSON.parseArray(entry.getSimilarQuestionsJson(), String.class);
                for (String sq : similarQuestions) {
                    if (normalize(sq).contains(normalizedQuery) || normalizedQuery.contains(normalize(sq))) {
                        score += 4;
                        break;
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // 检查关键词命中
        if (StringUtils.hasText(entry.getKeywords())) {
            String[] keywords = entry.getKeywords().split(",");
            for (String kw : keywords) {
                if (normalizedQuery.contains(normalize(kw.trim()))) {
                    score += 1;
                }
            }
        }

        return score;
    }

    private String normalize(String text) {
        if (text == null) return "";
        return text.trim().replaceAll("\\s+", "").toLowerCase();
    }

    private void incrementHitCount(FaqEntry entry) {
        try {
            entry.setHitCount((entry.getHitCount() == null ? 0 : entry.getHitCount()) + 1);
            faqEntryMapper.updateById(entry);
        } catch (Exception e) {
            log.warn("Failed to increment FAQ hit count: {}", e.getMessage());
        }
    }

    /**
     * FAQ匹配结果
     */
    public record FaqMatchResult(String faqId, String question, String answer, String category,
                                 String matchMethod, Double matchScore, List<String> sourceRefs,
                                 String version, String updatedAt, Map<String, Object> applicableScope) {
        public static FaqMatchResult from(FaqEntry entry, String matchMethod, Double matchScore) {
            Map<String, Object> scope = new HashMap<>();
            if (StringUtils.hasText(entry.getCategory())) {
                scope.put("category", entry.getCategory());
            }
            if (StringUtils.hasText(entry.getRegion())) {
                scope.put("region", entry.getRegion());
            }
            if (StringUtils.hasText(entry.getAudience())) {
                scope.put("audience", entry.getAudience());
            }
            String updatedAt = entry.getEditTime() == null ? null : entry.getEditTime().toInstant().toString();
            return new FaqMatchResult(
                    entry.getFaqId(),
                    entry.getQuestion(),
                    entry.getAnswer(),
                    entry.getCategory(),
                    matchMethod,
                    matchScore,
                    List.of("faq:" + entry.getFaqId()),
                    String.valueOf(entry.getId()),
                    updatedAt,
                    scope
            );
        }
    }
}
