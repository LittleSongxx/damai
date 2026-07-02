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
@TableName("d_ai_customer_work_item")
public class CustomerWorkItem extends BaseTableData {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String workItemId;

    private String runId;

    private String ticketId;

    private Long userId;

    private String skillGroup;

    private String workStatus;

    private String takeoverStatus;

    private String priority;

    private Date slaDueAt;

    private String assignedTo;

    private String conclusion;

    private Integer satisfactionScore;

    private String operatorId;

    private String extJson;
}
