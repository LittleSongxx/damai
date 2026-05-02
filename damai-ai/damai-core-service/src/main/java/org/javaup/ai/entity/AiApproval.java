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
@TableName("d_ai_approval")
public class AiApproval extends BaseTableData {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String approvalId;

    private String runId;

    private Long userId;

    private String chatId;

    private String approvalType;

    private String approvalStatus;

    private String previewJson;

    private String resultJson;

    private Date expiresAt;

    private Date approvedAt;

    private Date rejectedAt;
}
