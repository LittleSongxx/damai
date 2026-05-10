package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.javaup.ai.entity.base.BaseTableData;

@Data
@TableName("d_ai_prompt_version")
public class AiPromptVersion extends BaseTableData {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String promptKey;
    private Integer version;
    private String template;
    private String description;
    private Boolean active;
    private Long createdBy;
}
