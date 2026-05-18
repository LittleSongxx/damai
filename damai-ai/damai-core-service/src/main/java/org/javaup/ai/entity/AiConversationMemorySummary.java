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
@TableName("d_ai_conversation_memory_summary")
public class AiConversationMemorySummary extends BaseTableData {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String conversationId;

    private Long userId;

    private String coveredRunId;

    private String summary;

    private String memoryJson;

    private Integer summaryVersion;

    private Integer compressionCount;

    private String sourceProvenance;

    private Date expiresAt;
}
