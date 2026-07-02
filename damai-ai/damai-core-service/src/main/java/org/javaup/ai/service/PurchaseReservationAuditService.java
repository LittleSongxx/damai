package org.javaup.ai.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.assistant.skill.business.PurchaseActionSnapshot;
import org.javaup.ai.assistant.skill.business.TicketReservation;
import org.javaup.ai.entity.AiPurchaseReservationAction;
import org.javaup.ai.mapper.AiPurchaseReservationActionMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Date;

@Service
@RequiredArgsConstructor
public class PurchaseReservationAuditService {

    private final AiPurchaseReservationActionMapper mapper;

    public void reserved(String runId, String actionId, PurchaseActionSnapshot snapshot, TicketReservation reservation, String idempotencyKey) {
        if (reservation == null || !StringUtils.hasText(reservation.reservationId())) {
            return;
        }
        AiPurchaseReservationAction row = new AiPurchaseReservationAction();
        row.setReservationId(reservation.reservationId());
        row.setRunId(runId);
        row.setActionId(actionId);
        row.setProgramId(snapshot.getProgramId());
        row.setTicketCategoryId(snapshot.getTicketCategoryId());
        row.setTicketCount(snapshot.getTicketCount());
        row.setReservationStatus("RESERVED");
        row.setIdempotencyKey(idempotencyKey);
        row.setExpiresAt(reservation.expiresAt());
        row.setCreateTime(new Date());
        row.setEditTime(new Date());
        row.setStatus(1);
        mapper.insert(row);
    }

    public void confirmed(String reservationId, String orderNumber) {
        update(reservationId, "CONFIRMED", null, null, orderNumber);
    }

    public void released(String reservationId, String reason) {
        update(reservationId, "RELEASED", reason, null, null);
    }

    public void failed(String reservationId, String message) {
        update(reservationId, "FAILED", null, message, null);
    }

    private void update(String reservationId, String status, String releaseReason, String failureMessage, String orderNumber) {
        if (!StringUtils.hasText(reservationId)) {
            return;
        }
        AiPurchaseReservationAction row = mapper.selectOne(Wrappers.lambdaQuery(AiPurchaseReservationAction.class)
                .eq(AiPurchaseReservationAction::getReservationId, reservationId)
                .eq(AiPurchaseReservationAction::getStatus, 1)
                .last("limit 1"));
        if (row == null) {
            return;
        }
        row.setReservationStatus(status);
        row.setEditTime(new Date());
        if (releaseReason != null) {
            row.setReleaseReason(releaseReason);
            row.setReleasedAt(new Date());
        }
        if (failureMessage != null) {
            row.setFailureMessage(failureMessage);
        }
        if (orderNumber != null) {
            row.setOrderNumber(orderNumber);
        }
        mapper.updateById(row);
    }
}
