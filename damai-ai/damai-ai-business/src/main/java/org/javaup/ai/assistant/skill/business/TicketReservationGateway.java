package org.javaup.ai.assistant.skill.business;

public interface TicketReservationGateway {

    default TicketReservation reserve(PurchaseActionSnapshot snapshot, String idempotencyKey) {
        return reserve(snapshot, idempotencyKey, null, null);
    }

    TicketReservation reserve(PurchaseActionSnapshot snapshot, String idempotencyKey, String runId, String actionId);

    String confirm(PurchaseActionSnapshot snapshot, TicketReservation reservation, String idempotencyKey);

    void release(String reservationId, String reason);

    default TicketReservation status(String reservationId) {
        throw new UnsupportedOperationException("reservation status query is not implemented");
    }
}
