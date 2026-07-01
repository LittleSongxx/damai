package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.javaup.ai.entity.base.BaseTableData;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("d_ai_prompt_release_record")
public class AiPromptReleaseRecord extends BaseTableData {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String releaseId;

    private String promptKey;

    private Integer fromVersion;

    private Integer toVersion;

    private String actionType;

    private String rolloutStatus;

    private Integer trafficPercent;

    private String baselineEvalRunId;

    private String releaseNote;

    private String releaseEvidenceJson;

    private String rollbackReason;

    private Long operatorId;
}
