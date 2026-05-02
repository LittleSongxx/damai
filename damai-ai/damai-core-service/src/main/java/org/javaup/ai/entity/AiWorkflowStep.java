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
@TableName("d_ai_workflow_step")
public class AiWorkflowStep extends BaseTableData {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String runId;

    private Integer stepOrder;

    private String stepKey;

    private String stepStatus;

    private String inputJson;

    private String outputJson;

    private String errorMessage;

    private Date startedAt;

    private Date finishedAt;
}
