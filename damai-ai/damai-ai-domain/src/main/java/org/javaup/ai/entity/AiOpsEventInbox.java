package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.javaup.ai.entity.base.BaseTableData;

import java.util.Date;

@Data
@TableName("d_ai_ops_event_inbox")
public class AiOpsEventInbox extends BaseTableData {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String eventId;

    private String consumeStatus;

    private Date firstSeenAt;

    private Date lastSeenAt;

    private Integer duplicateCount;

    private String lastError;

    private String extJson;
}
