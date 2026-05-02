package org.javaup.ai.service;

import org.javaup.ai.entity.AiApproval;
import org.javaup.ai.entity.AiRetrievalTrace;
import org.javaup.ai.entity.AiToolAudit;
import org.javaup.ai.entity.AiWorkflowRun;
import org.javaup.ai.entity.AiWorkflowStep;

import java.util.List;
import java.util.Map;

public interface AiWorkflowService {

    AiWorkflowRun createRun(Integer type, String chatId, String requestType, String prompt);

    void updateRunContext(String runId, Map<String, Object> context);

    void recordStep(String runId, String stepKey, String stepStatus, Object input, Object output, String errorMessage);

    void markWaitingApproval(String runId, String stepKey, String responseSummary, String approvalId);

    void markCompleted(String runId, String stepKey, String responseSummary);

    void markFailed(String runId, String stepKey, String errorMessage);

    AiApproval createApproval(String runId, String chatId, String approvalType, Object previewData);

    AiApproval getPendingApproval(String runId);

    AiApproval approve(String runId);

    AiApproval reject(String runId);

    void saveToolAudit(AiToolAudit toolAudit);

    AiRetrievalTrace saveRetrievalTrace(AiRetrievalTrace retrievalTrace);

    AiWorkflowRun getRun(String runId);

    List<AiWorkflowStep> getSteps(String runId);
}
