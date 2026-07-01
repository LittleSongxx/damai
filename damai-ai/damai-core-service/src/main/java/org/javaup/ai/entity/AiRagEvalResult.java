package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.javaup.ai.entity.base.BaseTableData;

@Data
@TableName("d_ai_rag_eval_result")
public class AiRagEvalResult extends BaseTableData {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String evalRunId;
    private String caseId;
    private String question;
    private String retrievedChunks;
    private String generatedAnswer;
    private String datasetId;
    private String datasetVersion;
    private String retrievalConfigId;
    private String judgeConfigId;
    private String caseType;
    private String tags;
    @TableField("recall_at_1")
    private Double recallAt1;
    @TableField("recall_at_3")
    private Double recallAt3;
    @TableField("recall_at_5")
    private Double recallAt5;
    @TableField("recall_at_10")
    private Double recallAt10;
    @TableField("precision_at_1")
    private Double precisionAt1;
    @TableField("precision_at_3")
    private Double precisionAt3;
    @TableField("precision_at_5")
    private Double precisionAt5;
    @TableField("precision_at_10")
    private Double precisionAt10;
    @TableField("hit_rate_at_1")
    private Double hitRateAt1;
    @TableField("hit_rate_at_3")
    private Double hitRateAt3;
    @TableField("hit_rate_at_5")
    private Double hitRateAt5;
    @TableField("hit_rate_at_10")
    private Double hitRateAt10;
    private Double mrr;
    @TableField("ndcg_at_1")
    private Double ndcgAt1;
    @TableField("ndcg_at_3")
    private Double ndcgAt3;
    @TableField("ndcg_at_5")
    private Double ndcgAt5;
    @TableField("ndcg_at_10")
    private Double ndcgAt10;
    private Double faithfulnessScore;
    private Long latencyMs;
    private Long retrievalLatencyMs;
    private Long rerankLatencyMs;
    private Long generationLatencyMs;
    private Long totalLatencyMs;
    private Double contextPrecision;
    private Double contextRecall;
    private Double contextRelevance;
    private Double answerRelevancyScore;
    private Double answerCorrectnessScore;
    private Double unsupportedClaimRate;
    private Integer supportedClaimCount;
    private Integer unsupportedClaimCount;
    private Double requiredFactCoverage;
    private Double citationPrecision;
    private Double citationRecall;
    private Double citationCoverage;
    private Double refusalCorrectness;
    private Double safetyScore;
    private Double judgeRelevance;
    private Double judgeCoverage;
    private Double judgeContradiction;
    private Double judgeCitationSupport;
    private Double judgeAnswerability;
    private String judgeRefusalReason;
    private String judgeStructuredOutput;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer totalTokens;
    private Double estimatedCost;
    private String denseHitsJson;
    private String sparseHitsJson;
    private String fusedHitsJson;
    private String finalHitsJson;
    private String retrievalTraceId;
    private String retrievalPath;
    private String judgeRawOutput;
    private String failureType;
    private String evalMethod;
}
