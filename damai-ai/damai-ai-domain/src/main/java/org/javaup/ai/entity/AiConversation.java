package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.javaup.ai.entity.base.BaseTableData;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("d_ai_conversation")
public class AiConversation extends BaseTableData {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String conversationId;

    private Long userId;

    private String title;

    private String routeType;

    private String latestRunId;

    private String latestStatus;
}
