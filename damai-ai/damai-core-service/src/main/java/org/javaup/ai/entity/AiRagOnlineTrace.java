package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.javaup.ai.entity.base.BaseTableData;

@Data
@TableName("d_ai_rag_online_trace")
public class AiRagOnlineTrace extends BaseTableData {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String traceId;
    private String conversationId;
    private String runId;
    private Long userId;
    private String question;
    private String rewrittenQuery;
    private String subQuestionsJson;
    private String retrievedChunksJson;
    private String finalChunksJson;
    private String generatedAnswer;
    private String citationsJson;
    private String retrievalConfigId;
    private String modelName;
    private String promptVersion;
    private Long rewriteLatencyMs;
    private Long retrievalLatencyMs;
    private Long rerankLatencyMs;
    private Long generationLatencyMs;
    private Long totalLatencyMs;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer totalTokens;
    private Double estimatedCost;
    private Integer cacheHit;
    private String confidenceLevel;
    private Double confidenceScore;
    private String errorCode;
    private String errorMessage;
    private String feedbackType;
    private String metadataJson;
}
