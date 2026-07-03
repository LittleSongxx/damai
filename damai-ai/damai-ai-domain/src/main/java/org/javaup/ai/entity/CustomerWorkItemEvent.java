package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.javaup.ai.entity.base.BaseTableData;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("d_ai_customer_work_item_event")
public class CustomerWorkItemEvent extends BaseTableData {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String eventId;

    private String workItemId;

    private String eventType;

    private String operatorId;

    private String eventPayloadJson;
}
