package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.javaup.ai.entity.base.BaseTableData;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("d_ai_skill_eval_run")
public class AiSkillEvalRun extends BaseTableData {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String evalRunId;

    private String skillId;

    private Long userId;

    private String runStatus;

    private Integer caseCount;

    private Integer passedCount;

    private String resultJson;

    private String errorMessage;
}
