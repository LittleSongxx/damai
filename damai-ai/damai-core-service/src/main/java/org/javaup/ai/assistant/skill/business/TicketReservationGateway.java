package org.javaup.ai.assistant.skill.business;

public interface TicketReservationGateway {

    TicketReservation reserve(PurchaseActionSnapshot snapshot, String idempotencyKey);

    String confirm(PurchaseActionSnapshot snapshot, TicketReservation reservation, String idempotencyKey);

    void release(String reservationId, String reason);
}
