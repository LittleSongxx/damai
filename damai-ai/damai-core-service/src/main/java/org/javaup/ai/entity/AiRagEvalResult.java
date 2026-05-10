package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.javaup.ai.entity.base.BaseTableData;

@Data
@TableName("d_ai_rag_eval_result")
public class AiRagEvalResult extends BaseTableData {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String evalRunId;
    private String caseId;
    private String question;
    private String retrievedChunks;
    private String generatedAnswer;
    private Double recallAt5;
    private Double mrr;
    private Double ndcgAt5;
    private Double faithfulnessScore;
    private Long latencyMs;
}
