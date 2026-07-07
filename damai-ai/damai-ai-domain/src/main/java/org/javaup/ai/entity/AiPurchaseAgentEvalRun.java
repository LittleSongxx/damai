package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.javaup.ai.entity.base.BaseTableData;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("d_ai_purchase_agent_eval_run")
public class AiPurchaseAgentEvalRun extends BaseTableData {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String evalRunId;

    private String datasetId;

    private String datasetVersion;

    private Integer totalCases;

    private Integer completedCases;

    private Double slotAccuracy;

    private Double toolCallAccuracy;

    private Double parameterAccuracy;

    private Double trajectoryPassRate;

    private Double idempotencyPassRate;

    private Double reservationReleaseRate;

    private Integer approvalBypassCount;

    private Double p95LatencyMs;

    private String runStatus;

    private String qualityGateJson;

    private String requestJson;

    private String reportJson;

    private String errorMessage;
}
