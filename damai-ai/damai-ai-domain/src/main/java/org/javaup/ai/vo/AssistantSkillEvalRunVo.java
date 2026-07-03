package org.javaup.ai.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AssistantSkillEvalRunVo {

    private String evalRunId;

    private String skillId;

    private String runStatus;

    private Integer caseCount;

    private Integer passedCount;

    private String resultJson;
}
