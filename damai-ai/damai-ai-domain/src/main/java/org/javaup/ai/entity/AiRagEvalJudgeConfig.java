package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.javaup.ai.entity.base.BaseTableData;

@Data
@TableName("d_ai_rag_eval_judge_config")
public class AiRagEvalJudgeConfig extends BaseTableData {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String judgeConfigId;
    private String configName;
    private String judgeModel;
    private Double temperature;
    private String promptVersion;
    private String metricName;
    private String rubric;
    private String outputSchema;
    private Integer active;
}
