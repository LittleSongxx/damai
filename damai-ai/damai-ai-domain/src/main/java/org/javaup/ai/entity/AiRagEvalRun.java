package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.javaup.ai.entity.base.BaseTableData;

@Data
@TableName("d_ai_rag_eval_run")
public class AiRagEvalRun extends BaseTableData {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String evalRunId;
    private Integer totalCases;
    private Integer completedCases;
    private Double avgRecall;
    private Double avgPrecision;
    private Double avgHitRate;
    private Double avgMrr;
    private Double avgNdcg;
    private Double avgFaithfulness;
    private Double avgAnswerRelevancy;
    private Double avgCompleteness;
    private Double avgCtxPrecision;
    private Double avgCtxRecall;
    private Double avgContextRelevance;
    private Double avgAnswerCorrectness;
    private String datasetId;
    private String datasetVersion;
    private String retrievalConfigId;
    private String judgeConfigId;
    private String baselineRunId;
    private String requestJson;
    private String reportJson;
    private String qualityGateJson;
    private String gitCommit;
    private String modelVersion;
    private String promptVersion;
    private String runStatus;
    private String errorMessage;
}
