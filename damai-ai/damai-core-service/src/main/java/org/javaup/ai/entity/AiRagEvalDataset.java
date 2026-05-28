package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.javaup.ai.entity.base.BaseTableData;

@Data
@TableName("d_ai_rag_eval_dataset")
public class AiRagEvalDataset extends BaseTableData {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String datasetId;
    private String datasetName;
    private String datasetVersion;
    private String description;
    private Integer caseCount;
    private String kbVersion;
    private String chunkVersion;
    private String embeddingModel;
    private String createdBy;
    private String reviewedBy;
    private String reviewStatus;
}
