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
@TableName("d_ai_action")
public class AiAction extends BaseTableData {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String actionId;

    private String runId;

    private String conversationId;

    private Long userId;

    private String actionType;

    private String actionStatus;

    private String previewJson;

    private String resultJson;

    private Date expiresAt;

    private Date approvedAt;

    private Date rejectedAt;
}
