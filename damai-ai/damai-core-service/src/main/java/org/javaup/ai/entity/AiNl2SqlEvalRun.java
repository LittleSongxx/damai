package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.javaup.ai.entity.base.BaseTableData;

@Data
@TableName("d_ai_nl2sql_eval_run")
public class AiNl2SqlEvalRun extends BaseTableData {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String evalRunId;
    private Integer totalCases;
    private Integer completedCases;
    private Double sqlValidityRate;
    private Double executionAccuracy;
    private Double exactMatchRate;
    private Double resultSetEquivalenceRate;
    private Double schemaLinkPrecision;
    private Double schemaLinkRecall;
    private Double repairSuccessRate;
    private Double unsafeRejectionRate;
    private Double lowConfidenceClarificationRate;
    private Double avgLatencyMs;
    private Double avgEstimatedCost;
    private String runStatus;
    private String errorMessage;
}
