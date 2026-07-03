package org.javaup.ai.assistant.skill.business;

import com.alibaba.fastjson2.JSON;
import org.javaup.ai.ai.function.call.UserCall;
import org.javaup.ai.assistant.AssistantActionStatus;
import org.javaup.ai.assistant.AssistantRunService;
import org.javaup.ai.assistant.AssistantRunStatus;
import org.javaup.ai.context.AiRequestContext;
import org.javaup.ai.context.AiRequestContextHolder;
import org.javaup.ai.context.AiUserContext;
import org.javaup.ai.dto.ProgramOrderCreateDto;
import org.javaup.ai.entity.AiAction;
import org.javaup.ai.entity.AiRun;
import org.javaup.ai.service.PurchaseReservationAuditService;
import org.javaup.ai.vo.AssistantActionResultVo;
import org.javaup.ai.vo.ProgramDetailVo;
import org.javaup.ai.vo.TicketCategoryVo;
import org.javaup.ai.vo.TicketUserVo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PurchaseActionServiceTest {

    private final AssistantRunService assistantRunService = mock(AssistantRunService.class);
    private final ProgramQueryService programQueryService = mock(ProgramQueryService.class);
    private final UserCall userCall = mock(UserCall.class);
    private final TicketReservationGateway reservationGateway = mock(TicketReservationGateway.class);
    private final PurchaseRiskPolicyService riskPolicyService = mock(PurchaseRiskPolicyService.class);
    private final PurchaseReservationAuditService auditService = mock(PurchaseReservationAuditService.class);

    private final PurchaseActionService service = new PurchaseActionService(
            assistantRunService,
            programQueryService,
            userCall,
            reservationGateway,
            riskPolicyService,
            auditService);

    @BeforeEach
    void setUp() {
        AiRequestContextHolder.set(AiRequestContext.builder()
                .user(AiUserContext.builder()
                        .userId(1001L)
                        .mobile("13800000000")
                        .build())
                .build());
    }

    @AfterEach
    void tearDown() {
        AiRequestContextHolder.clear();
    }

    @Test
    void confirmUnknownShouldKeepOrderingAndNotReleaseReservation() {
        AiAction action = action();
        AiRun run = run();
        TicketReservation reservation = new TicketReservation("air_1", new Date(System.currentTimeMillis() + 60000L),
                true, "reserved", "RESERVED", null, null, false, false, 0L);
        ReservationGatewayException unknown = new ReservationGatewayException("confirm read timeout",
                FailureCategory.SIDE_EFFECT_UNKNOWN, true, true);

        when(assistantRunService.getAction("run_1", "action_1")).thenReturn(action);
        when(assistantRunService.claimActionForApproval(eq("run_1"), eq("action_1"), any(Date.class))).thenReturn(true);
        when(assistantRunService.getRunInternal("run_1")).thenReturn(run);
        when(userCall.ticketUserList(1001L)).thenReturn(List.of(ticketUser()));
        when(programQueryService.getProgramDetailRawById(2001L)).thenReturn(program());
        when(reservationGateway.reserve(any(), eq("idem-1:reservation"), eq("run_1"), eq("action_1"))).thenReturn(reservation);
        doThrow(unknown).when(reservationGateway).confirm(any(), eq(reservation), eq("idem-1:reservation:air_1"));
        when(reservationGateway.status("air_1")).thenReturn(new TicketReservation("air_1",
                reservation.expiresAt(), true, "confirming", "UNKNOWN", null,
                FailureCategory.SIDE_EFFECT_UNKNOWN.name(), true, true, 3000L));

        AssistantActionResultVo result = service.approve("run_1", "action_1");

        assertEquals(AssistantActionStatus.ORDERING.name(), result.getStatus());
        assertEquals("订单状态正在确认中，请稍后查看结果；我们不会重复下单。", result.getMessage());
        verify(auditService).confirmRequested("air_1", "idem-1:reservation:air_1");
        verify(auditService).unknown("air_1", FailureCategory.SIDE_EFFECT_UNKNOWN, "confirm read timeout");
        verify(reservationGateway, never()).release(eq("air_1"), any());
        verify(assistantRunService).markActionUnknown(eq(action), eq("ORDER_RESULT_UNKNOWN"),
                eq("confirm read timeout"), any(AssistantActionResultVo.class));
    }

    @Test
    void confirmUnknownRecoveredByStatusShouldCompleteAction() {
        AiAction action = action();
        AiRun run = run();
        TicketReservation reservation = new TicketReservation("air_2", new Date(System.currentTimeMillis() + 60000L),
                true, "reserved", "RESERVED", null, null, false, false, 0L);
        ReservationGatewayException unknown = new ReservationGatewayException("confirm read timeout",
                FailureCategory.SIDE_EFFECT_UNKNOWN, true, true);

        when(assistantRunService.getAction("run_1", "action_1")).thenReturn(action);
        when(assistantRunService.claimActionForApproval(eq("run_1"), eq("action_1"), any(Date.class))).thenReturn(true);
        when(assistantRunService.getRunInternal("run_1")).thenReturn(run);
        when(userCall.ticketUserList(1001L)).thenReturn(List.of(ticketUser()));
        when(programQueryService.getProgramDetailRawById(2001L)).thenReturn(program());
        when(reservationGateway.reserve(any(), eq("idem-1:reservation"), eq("run_1"), eq("action_1"))).thenReturn(reservation);
        doThrow(unknown).when(reservationGateway).confirm(any(), eq(reservation), eq("idem-1:reservation:air_2"));
        when(reservationGateway.status("air_2")).thenReturn(new TicketReservation("air_2",
                reservation.expiresAt(), false, "confirmed", "CONFIRMED", "order_1",
                null, false, false, 0L));

        AssistantActionResultVo result = service.approve("run_1", "action_1");

        assertEquals(AssistantActionStatus.COMPLETED.name(), result.getStatus());
        assertEquals("order_1", result.getOrderNumber());
        verify(auditService).confirmed("air_2", "order_1");
        verify(assistantRunService).markActionCompleted(eq(action), any(AssistantActionResultVo.class), eq("order_1"));
        verify(reservationGateway, never()).release(eq("air_2"), any());
    }

    private AiAction action() {
        AiAction action = new AiAction();
        action.setActionId("action_1");
        action.setRunId("run_1");
        action.setUserId(1001L);
        action.setActionStatus(AssistantActionStatus.WAITING.name());
        action.setPreviewJson(JSON.toJSONString(snapshot()));
        action.setIdempotencyKey("idem-1");
        action.setExpiresAt(new Date(System.currentTimeMillis() + 60000L));
        action.setVersion(0);
        action.setStatus(1);
        return action;
    }

    private AiRun run() {
        AiRun run = new AiRun();
        run.setRunId("run_1");
        run.setConversationId("chat_1");
        run.setUserId(1001L);
        run.setRunStatus(AssistantRunStatus.WAITING_ACTION.name());
        run.setStatus(1);
        return run;
    }

    private PurchaseActionSnapshot snapshot() {
        ProgramOrderCreateDto orderCreate = new ProgramOrderCreateDto();
        orderCreate.setProgramId(2001L);
        orderCreate.setUserId(1001L);
        orderCreate.setTicketCategoryId(3001L);
        orderCreate.setTicketCount(1);
        orderCreate.setTicketUserIdList(List.of(4001L));

        PurchaseActionSnapshot snapshot = new PurchaseActionSnapshot();
        snapshot.setProgramId(2001L);
        snapshot.setUserId(1001L);
        snapshot.setUserMobile("13800000000");
        snapshot.setTicketCategoryId(3001L);
        snapshot.setTicketCategoryPrice(new BigDecimal("188.00"));
        snapshot.setTicketCount(1);
        snapshot.setTicketUserIds(List.of(4001L));
        snapshot.setGeneratedAt(new Date());
        snapshot.setExpiresAt(new Date(System.currentTimeMillis() + 60000L));
        snapshot.setProgramOrderCreateDto(orderCreate);
        return snapshot;
    }

    private TicketUserVo ticketUser() {
        TicketUserVo ticketUser = new TicketUserVo();
        ticketUser.setId(4001L);
        ticketUser.setUserId(1001L);
        return ticketUser;
    }

    private ProgramDetailVo program() {
        TicketCategoryVo ticketCategory = new TicketCategoryVo();
        ticketCategory.setId(3001L);
        ticketCategory.setPrice(new BigDecimal("188.00"));
        ticketCategory.setRemainNumber(10L);

        ProgramDetailVo program = new ProgramDetailVo();
        program.setId(2001L);
        program.setTicketCategoryVoList(List.of(ticketCategory));
        return program;
    }
}
