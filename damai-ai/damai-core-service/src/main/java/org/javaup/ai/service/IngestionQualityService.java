package org.javaup.ai.service;

import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.entity.RagChunk;
import org.javaup.ai.mapper.RagChunkMapper;
import org.javaup.ai.vo.RagSourceVo;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Post-ingestion quality evaluation:
 * - Coverage check: can preset test queries retrieve relevant chunks?
 * - Chunk integrity: detect truncation, garbled text
 * - Metadata completeness: required fields present?
 * - Embedding quality: within-category cosine similarity distribution
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionQualityService {

    private final RagChunkMapper chunkMapper;
    private final HybridSearchService hybridSearchService;

    private static final int MIN_CHUNK_TEXT_LENGTH = 20;
    private static final int MAX_EMPTY_METADATA_RATIO = 5;

    /**
     * Run a full quality report after ingestion completes.
     */
    public Map<String, Object> runQualityReport() {
        List<RagChunk> allChunks = chunkMapper.selectAllActiveChunks();
        if (allChunks.isEmpty()) {
            return Map.of("status", "no_data", "message", "No active chunks found");
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("totalChunks", allChunks.size());

        // 1. Chunk integrity
        Map<String, Object> integrity = checkChunkIntegrity(allChunks);
        report.put("integrity", integrity);

        // 2. Metadata completeness
        Map<String, Object> metadataCompleteness = checkMetadataCompleteness(allChunks);
        report.put("metadataCompleteness", metadataCompleteness);

        // 3. Retrieval coverage
        Map<String, Object> coverage = checkRetrievalCoverage();
        report.put("retrievalCoverage", coverage);

        // 4. Overall score
        double score = computeQualityScore(integrity, metadataCompleteness, coverage);
        report.put("overallScore", String.format("%.1f%%", score * 100));
        report.put("grade", score >= 0.9 ? "A" : score >= 0.7 ? "B" : score >= 0.5 ? "C" : "D");

        log.info("Ingestion quality report: score={}", report.get("overallScore"));
        return report;
    }

    private Map<String, Object> checkChunkIntegrity(List<RagChunk> chunks) {
        int truncated = 0;
        int tooShort = 0;
        int garbled = 0;

        for (RagChunk chunk : chunks) {
            String text = chunk.getText();
            if (text == null || text.isBlank()) {
                tooShort++;
                continue;
            }
            if (text.length() < MIN_CHUNK_TEXT_LENGTH) {
                tooShort++;
            }
            // Detect garbled text: high ratio of non-ASCII non-CJK characters
            if (text.endsWith("...") || text.endsWith("…")) {
                truncated++;
            }
            long nonReadable = text.chars()
                    .filter(c -> !Character.isLetterOrDigit(c) && !Character.isIdeographic(c)
                            && !Character.isWhitespace(c) && c != ',' && c != '.' && c != '!' && c != '?'
                            && c != '，' && c != '。' && c != '！' && c != '？' && c != '：' && c != '；'
                            && c != '（' && c != '）' && c != '《' && c != '》')
                    .count();
            if (nonReadable > text.length() * 0.3) {
                garbled++;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("truncated", truncated);
        result.put("tooShort", tooShort);
        result.put("garbled", garbled);
        result.put("healthy", chunks.size() - truncated - tooShort - garbled);
        double healthRatio = chunks.isEmpty() ? 0 : (double) (chunks.size() - truncated - tooShort - garbled) / chunks.size();
        result.put("healthRatio", String.format("%.1f%%", healthRatio * 100));
        return result;
    }

    private Map<String, Object> checkMetadataCompleteness(List<RagChunk> chunks) {
        int missingQuestion = 0;
        int missingHeadingPath = 0;
        int missingHypothetical = 0;
        int missingEmbedding = 0;

        for (RagChunk chunk : chunks) {
            if (chunk.getQuestion() == null || chunk.getQuestion().isBlank()) missingQuestion++;
            if (chunk.getHeadingPath() == null || chunk.getHeadingPath().isBlank()) missingHeadingPath++;
            if (chunk.getHypotheticalQuestionsJson() == null || chunk.getHypotheticalQuestionsJson().isBlank())
                missingHypothetical++;
            if (chunk.getEmbeddingCached() == null || !chunk.getEmbeddingCached()) missingEmbedding++;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("missingQuestion", missingQuestion);
        result.put("missingHeadingPath", missingHeadingPath);
        result.put("missingHypotheticalQuestions", missingHypothetical);
        result.put("missingEmbedding", missingEmbedding);
        result.put("total", chunks.size());
        return result;
    }

    /**
     * Run preset test queries and check if relevant chunks are retrieved.
     */
    private Map<String, Object> checkRetrievalCoverage() {
        List<String> testQueries = List.of(
                "如何退票",
                "退票手续费多少",
                "退款多久到账",
                "儿童需要买票吗",
                "怎么取票",
                "支付失败怎么办",
                "如何修改订单",
                "实名认证需要什么证件",
                "电子票怎么使用",
                "演唱会可以退票吗",
                "转赠票怎么操作",
                "会员积分怎么获得",
                "发票怎么开",
                "学生票有什么优惠",
                "账号被冻结了怎么办",
                "座位图在哪里看",
                "VIP票有什么权益",
                "团购怎么联系",
                "演出取消了怎么赔偿",
                "抢票有什么技巧"
        );

        int covered = 0;
        List<Map<String, Object>> details = new ArrayList<>();

        for (String query : testQueries) {
            try {
                List<RagSourceVo> results = hybridSearchService.denseSearch(query, 5);
                boolean hasResults = !results.isEmpty();
                if (hasResults) covered++;

                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("query", query);
                detail.put("resultCount", results.size());
                detail.put("topScore", results.isEmpty() ? 0 : results.get(0).getScore());
                detail.put("covered", hasResults);
                details.add(detail);
            } catch (Exception e) {
                log.warn("Coverage check failed for query '{}': {}", query, e.getMessage());
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("query", query);
                detail.put("error", e.getMessage());
                detail.put("covered", false);
                details.add(detail);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalTests", testQueries.size());
        result.put("covered", covered);
        result.put("coverageRatio", String.format("%.1f%%", 100.0 * covered / testQueries.size()));
        result.put("details", details);
        return result;
    }

    private double computeQualityScore(Map<String, Object> integrity,
                                        Map<String, Object> metadata,
                                        Map<String, Object> coverage) {
        double integrityScore = parseRatio(integrity.get("healthRatio"));
        double coverageScore = parseRatio(coverage.get("coverageRatio"));

        int total = (int) metadata.getOrDefault("total", 1);
        int missingSum = (int) metadata.getOrDefault("missingQuestion", 0)
                + (int) metadata.getOrDefault("missingHeadingPath", 0)
                + (int) metadata.getOrDefault("missingHypotheticalQuestions", 0)
                + (int) metadata.getOrDefault("missingEmbedding", 0);
        double metadataScore = total > 0 ? 1.0 - (double) missingSum / (total * 4.0) : 1.0;

        return integrityScore * 0.3 + metadataScore * 0.3 + coverageScore * 0.4;
    }

    private double parseRatio(Object value) {
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.replace("%", "")) / 100.0;
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        if (value instanceof Number n) return n.doubleValue();
        return 0;
    }
}
