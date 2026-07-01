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
@TableName("d_ai_escalation_ticket")
public class EscalationTicket extends BaseTableData {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String ticketId;

    private String runId;

    private String conversationId;

    private Long userId;

    private String escalationType;

    private String priority;

    private String ticketStatus;

    private String aiDiagnosis;

    private String dialogueSummary;

    private String contextJson;

    private String sentiment;

    private String intentCode;

    private String suggestedReply;

    private Long assignedTo;

    private String resolution;

    private Date resolvedAt;

    private Long resolvedBy;
}
