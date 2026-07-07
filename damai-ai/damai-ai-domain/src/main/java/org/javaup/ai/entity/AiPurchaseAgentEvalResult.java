package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.javaup.ai.entity.base.BaseTableData;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("d_ai_purchase_agent_eval_result")
public class AiPurchaseAgentEvalResult extends BaseTableData {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String evalRunId;

    private String caseId;

    private String question;

    private String finalStatus;

    private Double slotAccuracy;

    private Double toolCallAccuracy;

    private Double parameterAccuracy;

    private Integer trajectoryPassed;

    private Integer approvalBypassed;

    private Integer idempotencyPassed;

    private Integer reservationReleased;

    private Long latencyMs;

    private String expectedTraceJson;

    private String actualTraceJson;

    private String expectedSlotsJson;

    private String actualSlotsJson;

    private String failureReason;

    private String judgeRawOutput;

    private String evalMethod;
}
