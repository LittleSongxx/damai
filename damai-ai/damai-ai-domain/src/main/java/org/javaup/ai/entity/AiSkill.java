package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.javaup.ai.entity.base.BaseTableData;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("d_ai_skill")
public class AiSkill extends BaseTableData {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String skillId;

    private String name;

    private String description;

    private String version;

    private String goal;

    private String instructions;

    private String routeType;

    private String category;

    private String triggerKeywordsJson;

    private String toolAllowlistJson;

    private String examplesJson;

    private String evalCasesJson;

    private String inputSchemaJson;

    private String outputSchemaJson;

    private String riskLevel;

    private Integer requiresAdmin;

    private Integer requiresApproval;

    private Integer enabled;

    private String executorType;

    private Integer frontendSelectable;

    private Integer modelSelectable;

    private Integer primarySkill;
}
