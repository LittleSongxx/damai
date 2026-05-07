package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.javaup.ai.entity.base.BaseTableData;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("d_ai_skill_eval_case")
public class AiSkillEvalCase extends BaseTableData {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String caseId;

    private String skillId;

    private String question;

    private String expectedOutputJson;

    private String tagsJson;

    private Integer enabled;
}
