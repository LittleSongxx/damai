package org.javaup.ai.service.impl;

import org.javaup.ai.context.AiRequestContext;
import org.javaup.ai.context.AiRequestContextHolder;
import org.javaup.ai.context.AiUserContext;
import org.javaup.ai.entity.AiApproval;
import org.javaup.ai.entity.AiWorkflowRun;
import org.javaup.ai.mapper.AiApprovalMapper;
import org.javaup.ai.mapper.AiRetrievalTraceMapper;
import org.javaup.ai.mapper.AiToolAuditMapper;
import org.javaup.ai.mapper.AiWorkflowRunMapper;
import org.javaup.ai.mapper.AiWorkflowStepMapper;
import org.javaup.ai.service.ChatTypeHistoryService;
import org.javaup.ai.workflow.AiWorkflowStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiWorkflowServiceImplTest {

    @Mock
    private AiWorkflowRunMapper workflowRunMapper;
    @Mock
    private AiWorkflowStepMapper workflowStepMapper;
    @Mock
    private AiApprovalMapper approvalMapper;
    @Mock
    private AiToolAuditMapper toolAuditMapper;
    @Mock
    private AiRetrievalTraceMapper retrievalTraceMapper;
    @Mock
    private ChatTypeHistoryService chatTypeHistoryService;

    @InjectMocks
    private AiWorkflowServiceImpl workflowService;

    @Captor
    private ArgumentCaptor<AiWorkflowRun> runCaptor;

    @BeforeEach
    void setUpContext() {
        AiRequestContextHolder.set(AiRequestContext.builder()
                .conversationId("chat-1")
                .runId("run-1")
                .chatType(2)
                .requestType("assistant")
                .user(AiUserContext.builder().userId(1001L).token("token-1").build())
                .build());
    }

    @AfterEach
    void clearContext() {
        AiRequestContextHolder.clear();
    }

    @Test
    void shouldCreateUserScopedWorkflowRunAndBindHistory() {
        AiWorkflowRun run = workflowService.createRun(2, "chat-1", "assistant", "我要买票");

        verify(workflowRunMapper).insert(runCaptor.capture());
        verify(chatTypeHistoryService).save(2, "chat-1");
        verify(chatTypeHistoryService).bindLatestRun("chat-1", run.getRunId(), AiWorkflowStatus.RUNNING);
        assertEquals(1001L, runCaptor.getValue().getUserId());
        assertEquals("assistant", runCaptor.getValue().getRequestType());
        assertNotNull(run.getRunId());
    }

    @Test
    void shouldCreateApprovalWithExpiry() {
        AiApproval approval = workflowService.createApproval("run-1", "chat-1", "ORDER_CONFIRM", new Date());

        assertNotNull(approval.getApprovalId());
        assertEquals(1001L, approval.getUserId());
        assertTrue(approval.getExpiresAt().after(new Date()));
        verify(approvalMapper).insert(any(AiApproval.class));
    }

    @Test
    void shouldPreventReplayAfterApprovalAlreadyConsumed() {
        AiApproval approval = new AiApproval();
        approval.setRunId("run-1");
        approval.setApprovalStatus(AiWorkflowStatus.WAITING_APPROVAL);
        approval.setExpiresAt(new Date(System.currentTimeMillis() + 60000));
        when(approvalMapper.selectOne(any())).thenReturn(approval, null);

        AiApproval first = workflowService.approve("run-1");
        AiApproval second = workflowService.approve("run-1");

        assertEquals(AiWorkflowStatus.COMPLETED, first.getApprovalStatus());
        assertTrue(second == null);
        verify(approvalMapper, times(1)).updateById(approval);
    }
}
