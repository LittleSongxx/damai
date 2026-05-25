package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.javaup.ai.entity.base.BaseTableData;

import java.util.Date;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("d_ai_dialogue_state")
public class DialogueState extends BaseTableData {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String stateId;

    private String conversationId;

    private Long userId;

    private String intent;

    private String dialoguePhase;

    private String slotsJson;

    private String missingSlotsJson;

    private Integer turnCount;

    private Integer maxTurns;

    private String lastUserMessage;

    private String lastAssistantMessage;

    private Integer resolved;

    private Date resolveTime;
}
