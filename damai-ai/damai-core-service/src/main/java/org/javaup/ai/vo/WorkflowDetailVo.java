package org.javaup.ai.vo;

import lombok.Builder;
import lombok.Data;
import org.javaup.ai.entity.AiApproval;
import org.javaup.ai.entity.AiWorkflowRun;
import org.javaup.ai.entity.AiWorkflowStep;

import java.util.List;

@Data
@Builder
public class WorkflowDetailVo {

    private AiWorkflowRun run;

    private List<AiWorkflowStep> steps;

    private AiApproval pendingApproval;
}
