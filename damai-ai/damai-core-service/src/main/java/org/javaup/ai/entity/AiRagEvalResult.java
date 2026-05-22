package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
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
    @TableField("recall_at_5")
    private Double recallAt5;
    private Double mrr;
    @TableField("ndcg_at_5")
    private Double ndcgAt5;
    private Double faithfulnessScore;
    private Long latencyMs;
    private Double contextPrecision;
    private Double contextRecall;
    private Double contextRelevance;
    private Double answerRelevancyScore;
    private Double answerCorrectnessScore;
    private String evalMethod;
}
