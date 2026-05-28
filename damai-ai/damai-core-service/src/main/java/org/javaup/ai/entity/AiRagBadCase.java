package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.javaup.ai.entity.base.BaseTableData;

@Data
@TableName("d_ai_rag_bad_case")
public class AiRagBadCase extends BaseTableData {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String badCaseId;
    private String traceId;
    private String question;
    private String generatedAnswer;
    private String retrievedChunksJson;
    private String expectedAnswer;
    private String expectedChunks;
    private String feedbackType;
    private String failureType;
    private String category;
    private String difficulty;
    private String caseType;
    private String operatorNote;
    private Integer convertedToEvalCase;
    private String convertedCaseId;
    private String reviewStatus;
}
