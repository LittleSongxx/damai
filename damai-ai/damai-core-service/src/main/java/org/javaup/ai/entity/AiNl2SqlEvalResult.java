package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.javaup.ai.entity.base.BaseTableData;

@Data
@TableName("d_ai_nl2sql_eval_result")
public class AiNl2SqlEvalResult extends BaseTableData {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String evalRunId;
    private String caseId;
    private String question;
    private String generatedSql;
    private Integer isValidSql;
    private Integer executeSuccess;
    private Integer exactMatch;
    private Integer resultSetEquivalent;
    private Integer unsafeRejected;
    private Integer lowConfidenceClarified;
    private Integer repairAttempted;
    private Integer repairSucceeded;
    private Double schemaLinkPrecision;
    private Double schemaLinkRecall;
    private Double estimatedCost;
    private Long latencyMs;
    private String errorMessage;
    private String evalMethod;
    private String safetyReportJson;
    private String schemaLinkingEvidenceJson;
    private String repairTraceJson;
}
