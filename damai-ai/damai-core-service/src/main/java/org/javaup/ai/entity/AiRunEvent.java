package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.javaup.ai.entity.base.BaseTableData;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("d_ai_run_event")
public class AiRunEvent extends BaseTableData {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String eventId;

    private String runId;

    private String conversationId;

    private Long userId;

    private Integer eventOrder;

    private String eventType;

    private String payloadJson;
}
