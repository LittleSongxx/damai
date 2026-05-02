package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.javaup.ai.entity.base.BaseTableData;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("d_ai_tool_call")
public class AiToolCall extends BaseTableData {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String callId;

    private String runId;

    private String conversationId;

    private Long userId;

    private String toolName;

    private String toolType;

    private String inputJson;

    private String outputJson;

    private Long durationMs;

    private Integer success;

    private String errorMessage;
}
