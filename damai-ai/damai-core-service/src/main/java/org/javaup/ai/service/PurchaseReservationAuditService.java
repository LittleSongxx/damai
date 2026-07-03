package org.javaup.ai.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.assistant.skill.business.FailureCategory;
import org.javaup.ai.assistant.skill.business.PurchaseActionSnapshot;
import org.javaup.ai.assistant.skill.business.TicketReservation;
import org.javaup.ai.entity.AiPurchaseReservationAction;
import org.javaup.ai.mapper.AiPurchaseReservationActionMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Date;

@Service
@RequiredArgsConstructor
public class PurchaseReservationAuditService {

    private final AiPurchaseReservationActionMapper mapper;

    public void reserved(String runId, String actionId, PurchaseActionSnapshot snapshot, TicketReservation reservation, String idempotencyKey) {
        if (reservation == null || !StringUtils.hasText(reservation.reservationId())) {
            return;
        }
        AiPurchaseReservationAction row = find(reservation.reservationId());
        if (row == null) {
            row = new AiPurchaseReservationAction();
            row.setReservationId(reservation.reservationId());
            row.setRunId(runId);
            row.setActionId(actionId);
            row.setUserId(snapshot.getUserId());
            row.setProgramId(snapshot.getProgramId());
            row.setTicketCategoryId(snapshot.getTicketCategoryId());
            row.setTicketCount(snapshot.getTicketCount());
            row.setIdempotencyKey(idempotencyKey);
            row.setExpiresAt(reservation.expiresAt());
            row.setRequestHash(DigestUtils.md5DigestAsHex(snapshot.toString().getBytes(StandardCharsets.UTF_8)));
            row.setCreateTime(new Date());
            row.setStatus(1);
        }
        row.setReservationStatus("RESERVED");
        row.setSagaStatus("RESERVED");
        row.setCompensationStatus("NONE");
        row.setRetryCount(row.getRetryCount() == null ? 0 : row.getRetryCount());
        row.setEditTime(new Date());
        if (row.getId() == null) {
            mapper.insert(row);
        } else {
            mapper.updateById(row);
        }
    }

    public void confirmRequested(String reservationId, String confirmAttemptId) {
        update(reservationId, "CONFIRMING", null, null, null, "CONFIRM_REQUESTED", "NONE", confirmAttemptId);
    }

    public void confirmed(String reservationId, String orderNumber) {
        update(reservationId, "CONFIRMED", null, null, orderNumber, "CONFIRMED", "NONE", null);
    }

    public void released(String reservationId, String reason) {
        update(reservationId, "RELEASED", reason, null, null, "RELEASED", "DONE", null);
    }

    public void compensationPending(String reservationId, String reason) {
        update(reservationId, "UNKNOWN", reason, null, null, "COMPENSATION_PENDING", "PENDING", null);
    }

    public void unknown(String reservationId, FailureCategory category, String message) {
        update(reservationId, "UNKNOWN", null, message, null, "UNKNOWN", "CHECK_REQUIRED", null);
        AiPurchaseReservationAction row = find(reservationId);
        if (row != null) {
            row.setFailureCategory(category == null ? FailureCategory.SIDE_EFFECT_UNKNOWN.name() : category.name());
            row.setLastCheckedAt(new Date());
            row.setRetryCount(row.getRetryCount() == null ? 1 : row.getRetryCount() + 1);
            mapper.updateById(row);
        }
    }

    public void failed(String reservationId, String message) {
        update(reservationId, "FAILED", null, message, null, "FAILED", "NONE", null);
    }

    private void update(String reservationId,
                        String status,
                        String releaseReason,
                        String failureMessage,
                        String orderNumber,
                        String sagaStatus,
                        String compensationStatus,
                        String confirmAttemptId) {
        if (!StringUtils.hasText(reservationId)) {
            return;
        }
        AiPurchaseReservationAction row = find(reservationId);
        if (row == null) {
            return;
        }
        row.setReservationStatus(status);
        row.setSagaStatus(sagaStatus);
        row.setCompensationStatus(compensationStatus);
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
        if (confirmAttemptId != null) {
            row.setConfirmAttemptId(confirmAttemptId);
        }
        mapper.updateById(row);
    }

    private AiPurchaseReservationAction find(String reservationId) {
        if (!StringUtils.hasText(reservationId)) {
            return null;
        }
        return mapper.selectOne(Wrappers.lambdaQuery(AiPurchaseReservationAction.class)
                .eq(AiPurchaseReservationAction::getReservationId, reservationId)
                .eq(AiPurchaseReservationAction::getStatus, 1)
                .last("limit 1"));
    }
}
