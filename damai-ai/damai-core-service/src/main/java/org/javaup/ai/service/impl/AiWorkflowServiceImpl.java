package org.javaup.ai.service.impl;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.javaup.ai.context.AiRequestContextHolder;
import org.javaup.ai.context.AiUserContext;
import org.javaup.ai.entity.AiApproval;
import org.javaup.ai.entity.AiRetrievalTrace;
import org.javaup.ai.entity.AiToolAudit;
import org.javaup.ai.entity.AiWorkflowRun;
import org.javaup.ai.entity.AiWorkflowStep;
import org.javaup.ai.mapper.AiApprovalMapper;
import org.javaup.ai.mapper.AiRetrievalTraceMapper;
import org.javaup.ai.mapper.AiToolAuditMapper;
import org.javaup.ai.mapper.AiWorkflowRunMapper;
import org.javaup.ai.mapper.AiWorkflowStepMapper;
import org.javaup.ai.service.AiWorkflowService;
import org.javaup.ai.service.ChatTypeHistoryService;
import org.javaup.ai.workflow.AiWorkflowStatus;
import org.javaup.ai.workflow.AiWorkflowStepStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AiWorkflowServiceImpl implements AiWorkflowService {

    private final AiWorkflowRunMapper workflowRunMapper;
    private final AiWorkflowStepMapper workflowStepMapper;
    private final AiApprovalMapper approvalMapper;
    private final AiToolAuditMapper toolAuditMapper;
    private final AiRetrievalTraceMapper retrievalTraceMapper;
    private final ChatTypeHistoryService chatTypeHistoryService;

    public AiWorkflowServiceImpl(AiWorkflowRunMapper workflowRunMapper,
                                 AiWorkflowStepMapper workflowStepMapper,
                                 AiApprovalMapper approvalMapper,
                                 AiToolAuditMapper toolAuditMapper,
                                 AiRetrievalTraceMapper retrievalTraceMapper,
                                 ChatTypeHistoryService chatTypeHistoryService) {
        this.workflowRunMapper = workflowRunMapper;
        this.workflowStepMapper = workflowStepMapper;
        this.approvalMapper = approvalMapper;
        this.toolAuditMapper = toolAuditMapper;
        this.retrievalTraceMapper = retrievalTraceMapper;
        this.chatTypeHistoryService = chatTypeHistoryService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiWorkflowRun createRun(Integer type, String chatId, String requestType, String prompt) {
        AiUserContext user = AiRequestContextHolder.getRequiredUser();
        chatTypeHistoryService.save(type, chatId);
        AiWorkflowRun run = new AiWorkflowRun();
        run.setRunId(nextId("run"));
        run.setUserId(user.getUserId());
        run.setChatId(chatId);
        run.setType(type);
        run.setRequestType(requestType);
        run.setWorkflowStatus(AiWorkflowStatus.RUNNING);
        run.setCurrentStep("INIT");
        run.setRequestSummary(prompt);
        run.setCreateTime(new Date());
        run.setEditTime(new Date());
        run.setStatus(1);
        workflowRunMapper.insert(run);
        chatTypeHistoryService.bindLatestRun(chatId, run.getRunId(), AiWorkflowStatus.RUNNING);
        return run;
    }

    @Override
    public void updateRunContext(String runId, Map<String, Object> context) {
        AiWorkflowRun run = getRun(runId);
        if (run == null) {
            return;
        }
        run.setContextJson(JSON.toJSONString(context));
        run.setEditTime(new Date());
        workflowRunMapper.updateById(run);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordStep(String runId, String stepKey, String stepStatus, Object input, Object output, String errorMessage) {
        AiWorkflowStep step = new AiWorkflowStep();
        step.setRunId(runId);
        step.setStepKey(stepKey);
        step.setStepStatus(stepStatus);
        step.setInputJson(input == null ? null : JSON.toJSONString(input));
        step.setOutputJson(output == null ? null : JSON.toJSONString(output));
        step.setErrorMessage(errorMessage);
        step.setStepOrder(nextStepOrder(runId));
        Date now = new Date();
        step.setStartedAt(now);
        step.setFinishedAt(now);
        step.setCreateTime(now);
        step.setEditTime(now);
        step.setStatus(1);
        workflowStepMapper.insert(step);

        AiWorkflowRun run = getRun(runId);
        if (run != null) {
            run.setCurrentStep(stepKey);
            run.setEditTime(now);
            workflowRunMapper.updateById(run);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markWaitingApproval(String runId, String stepKey, String responseSummary, String approvalId) {
        AiWorkflowRun run = getRun(runId);
        if (run == null) {
            return;
        }
        run.setCurrentStep(stepKey);
        run.setWorkflowStatus(AiWorkflowStatus.WAITING_APPROVAL);
        run.setResponseSummary(responseSummary);
        run.setLatestApprovalId(approvalId);
        run.setEditTime(new Date());
        workflowRunMapper.updateById(run);
        chatTypeHistoryService.bindLatestRun(run.getChatId(), runId, AiWorkflowStatus.WAITING_APPROVAL);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markCompleted(String runId, String stepKey, String responseSummary) {
        AiWorkflowRun run = getRun(runId);
        if (run == null) {
            return;
        }
        run.setCurrentStep(stepKey);
        run.setWorkflowStatus(AiWorkflowStatus.COMPLETED);
        run.setResponseSummary(responseSummary);
        run.setCompletedAt(new Date());
        run.setEditTime(new Date());
        workflowRunMapper.updateById(run);
        chatTypeHistoryService.bindLatestRun(run.getChatId(), runId, AiWorkflowStatus.COMPLETED);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markFailed(String runId, String stepKey, String errorMessage) {
        AiWorkflowRun run = getRun(runId);
        if (run == null) {
            return;
        }
        run.setCurrentStep(stepKey);
        run.setWorkflowStatus(AiWorkflowStatus.FAILED);
        run.setErrorMessage(errorMessage);
        run.setCompletedAt(new Date());
        run.setEditTime(new Date());
        workflowRunMapper.updateById(run);
        chatTypeHistoryService.bindLatestRun(run.getChatId(), runId, AiWorkflowStatus.FAILED);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiApproval createApproval(String runId, String chatId, String approvalType, Object previewData) {
        AiUserContext user = AiRequestContextHolder.getRequiredUser();
        AiApproval approval = new AiApproval();
        approval.setApprovalId(nextId("approval"));
        approval.setRunId(runId);
        approval.setUserId(user.getUserId());
        approval.setChatId(chatId);
        approval.setApprovalType(approvalType);
        approval.setApprovalStatus(AiWorkflowStatus.WAITING_APPROVAL);
        approval.setPreviewJson(JSON.toJSONString(previewData));
        approval.setExpiresAt(new Date(System.currentTimeMillis() + 15 * 60 * 1000L));
        approval.setCreateTime(new Date());
        approval.setEditTime(new Date());
        approval.setStatus(1);
        approvalMapper.insert(approval);
        return approval;
    }

    @Override
    public AiApproval getPendingApproval(String runId) {
        Date now = new Date();
        LambdaQueryWrapper<AiApproval> query = new LambdaQueryWrapper<AiApproval>()
                .eq(AiApproval::getRunId, runId)
                .eq(AiApproval::getApprovalStatus, AiWorkflowStatus.WAITING_APPROVAL)
                .gt(AiApproval::getExpiresAt, now)
                .eq(AiApproval::getStatus, 1)
                .last("limit 1");
        AiRequestContextHolder.getOptional()
                .map(ctx -> ctx.getUser().getUserId())
                .ifPresent(userId -> query.eq(AiApproval::getUserId, userId));
        return approvalMapper.selectOne(query);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiApproval approve(String runId) {
        AiApproval approval = getPendingApproval(runId);
        if (approval == null) {
            return null;
        }
        approval.setApprovalStatus(AiWorkflowStatus.COMPLETED);
        approval.setApprovedAt(new Date());
        approval.setEditTime(new Date());
        approvalMapper.updateById(approval);
        return approval;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiApproval reject(String runId) {
        AiApproval approval = getPendingApproval(runId);
        if (approval == null) {
            return null;
        }
        approval.setApprovalStatus(AiWorkflowStatus.REJECTED);
        approval.setRejectedAt(new Date());
        approval.setEditTime(new Date());
        approvalMapper.updateById(approval);
        AiWorkflowRun run = getRun(runId);
        if (run != null) {
            run.setWorkflowStatus(AiWorkflowStatus.REJECTED);
            run.setCompletedAt(new Date());
            run.setEditTime(new Date());
            workflowRunMapper.updateById(run);
            chatTypeHistoryService.bindLatestRun(run.getChatId(), runId, AiWorkflowStatus.REJECTED);
        }
        return approval;
    }

    @Override
    public void saveToolAudit(AiToolAudit toolAudit) {
        Date now = new Date();
        toolAudit.setCreateTime(now);
        toolAudit.setEditTime(now);
        toolAudit.setStatus(1);
        toolAuditMapper.insert(toolAudit);
    }

    @Override
    public AiRetrievalTrace saveRetrievalTrace(AiRetrievalTrace retrievalTrace) {
        Date now = new Date();
        retrievalTrace.setCreateTime(now);
        retrievalTrace.setEditTime(now);
        retrievalTrace.setStatus(1);
        if (retrievalTrace.getTraceId() == null) {
            retrievalTrace.setTraceId(nextId("retrieval"));
        }
        retrievalTraceMapper.insert(retrievalTrace);
        return retrievalTrace;
    }

    @Override
    public AiWorkflowRun getRun(String runId) {
        LambdaQueryWrapper<AiWorkflowRun> query = new LambdaQueryWrapper<AiWorkflowRun>()
                .eq(AiWorkflowRun::getRunId, runId)
                .eq(AiWorkflowRun::getStatus, 1)
                .last("limit 1");
        AiRequestContextHolder.getOptional()
                .map(ctx -> ctx.getUser().getUserId())
                .ifPresent(userId -> query.eq(AiWorkflowRun::getUserId, userId));
        return workflowRunMapper.selectOne(query);
    }

    @Override
    public List<AiWorkflowStep> getSteps(String runId) {
        return workflowStepMapper.selectList(new LambdaQueryWrapper<AiWorkflowStep>()
                .eq(AiWorkflowStep::getRunId, runId)
                .eq(AiWorkflowStep::getStatus, 1)
                .orderByAsc(AiWorkflowStep::getStepOrder));
    }

    private int nextStepOrder(String runId) {
        Long count = workflowStepMapper.selectCount(new LambdaQueryWrapper<AiWorkflowStep>()
                .eq(AiWorkflowStep::getRunId, runId)
                .eq(AiWorkflowStep::getStatus, 1));
        return count == null ? 1 : count.intValue() + 1;
    }

    private String nextId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }
}
