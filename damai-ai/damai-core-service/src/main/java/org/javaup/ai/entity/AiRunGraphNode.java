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
@TableName("d_ai_run_graph_node")
public class AiRunGraphNode extends BaseTableData {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String nodeId;

    private String runId;

    private String conversationId;

    private Long userId;

    private String nodeType;

    private String nodeLabel;

    private Integer graphOrder;

    private String nodeStatus;

    private String latestEventId;

    private String latestEventType;

    private Integer latestEventOrder;

    private String checkpointId;

    private String checkpointStage;

    private String stateJson;

    private String inputSummary;

    private String outputSummary;

    private String riskLevel;

    private String traceRef;

    private Date startedAt;

    private Date completedAt;
}
