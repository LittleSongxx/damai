package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.javaup.ai.entity.base.BaseTableData;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("d_ai_tool_audit")
public class AiToolAudit extends BaseTableData {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String runId;

    private String chatId;

    private Long userId;

    private String toolName;

    private String toolType;

    private String requestSummary;

    private String responseSummary;

    private Boolean success;

    private String errorMessage;
}
