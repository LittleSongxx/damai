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
@TableName("d_ai_run")
public class AiRun extends BaseTableData {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String runId;

    private String conversationId;

    private Long userId;

    private String routeType;

    private String skillId;

    private String skillVersion;

    private String skillSnapshotJson;

    private String runStatus;

    private String currentStage;

    private String clientContextJson;

    private String userMessage;

    private String responseSummary;

    private String errorMessage;

    private Integer eventSeq;

    private Date completedAt;
}
