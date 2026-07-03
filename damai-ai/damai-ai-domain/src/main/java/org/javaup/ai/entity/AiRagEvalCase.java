package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.javaup.ai.entity.base.BaseTableData;

@Data
@TableName("d_ai_rag_eval_case")
public class AiRagEvalCase extends BaseTableData {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String caseId;
    private String question;
    private String expectedAnswer;
    private String expectedChunks;
    private String category;
    private String difficulty;
    private String datasetId;
    private String datasetVersion;
    private String caseType;
    private String tags;
    private String requiredFacts;
    private String forbiddenFacts;
    private String expectedCitations;
    private String reviewStatus;
}
