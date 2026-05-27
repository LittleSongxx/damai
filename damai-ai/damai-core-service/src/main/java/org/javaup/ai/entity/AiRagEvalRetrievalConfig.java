package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.javaup.ai.entity.base.BaseTableData;

@Data
@TableName("d_ai_rag_eval_retrieval_config")
public class AiRagEvalRetrievalConfig extends BaseTableData {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String configId;
    private String configName;
    private Boolean denseEnabled;
    private Boolean sparseEnabled;
    private Boolean hydeEnabled;
    private Boolean queryRewriteEnabled;
    private Boolean subQuestionEnabled;
    private Boolean rerankEnabled;
    private String rerankModel;
    private String embeddingModel;
    private Integer topK;
    private Integer candidateK;
    private Double denseWeight;
    private Double sparseWeight;
    private Double hydeWeight;
    private Integer rrfK;
    private Integer chunkSize;
    private Integer chunkOverlap;
    private Boolean parentChildEnabled;
    private String metadataJson;
}
