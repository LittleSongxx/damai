package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.javaup.ai.entity.base.BaseTableData;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("d_ai_skill_resource")
public class AiSkillResource extends BaseTableData {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String resourceId;

    private String skillId;

    private String resourceType;

    private String title;

    private String content;

    private String metadataJson;

    private Integer enabled;
}
