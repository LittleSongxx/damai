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
@TableName("d_ai_feedback_analysis")
public class FeedbackAnalysis extends BaseTableData {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String analysisId;

    private String feedbackId;

    private String runId;

    private String analysisType;

    private String issueSummary;

    private String suggestedAction;

    private String actionTaken;

    private String actionStatus;

    private String clusterKey;

    private Integer affectedFeedbackCount;

    private Long resolvedBy;

    private Date resolvedAt;
}
