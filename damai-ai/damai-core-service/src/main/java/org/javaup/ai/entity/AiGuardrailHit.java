package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.javaup.ai.entity.base.BaseTableData;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("d_ai_guardrail_hit")
public class AiGuardrailHit extends BaseTableData {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String hitId;

    private String runId;

    private String conversationId;

    private Long userId;

    private String stage;

    private String guardrailAction;

    private String ruleNames;

    private String contentPreview;
}
