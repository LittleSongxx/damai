package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.javaup.ai.entity.base.BaseTableData;

import java.util.Date;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("d_ai_workflow_run")
public class AiWorkflowRun extends BaseTableData {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String runId;

    private Long userId;

    private String chatId;

    private Integer type;

    private String requestType;

    private String workflowStatus;

    private String currentStep;

    private String latestApprovalId;

    private String requestSummary;

    private String responseSummary;

    private String contextJson;

    private String errorMessage;

    private Date completedAt;
}
