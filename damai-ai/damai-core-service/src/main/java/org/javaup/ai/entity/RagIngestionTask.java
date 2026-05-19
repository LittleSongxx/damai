package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.javaup.ai.entity.base.BaseTableData;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("d_ai_rag_ingestion_task")
public class RagIngestionTask extends BaseTableData {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String taskId;
    private String taskType;
    private String taskStatus;
    private String sourceFile;
    private String contentHash;
    private Integer totalChunks;
    private Integer completedChunks;
    private String errorMessage;
    private String resultJson;
    private String startedAt;
    private String finishedAt;
}
