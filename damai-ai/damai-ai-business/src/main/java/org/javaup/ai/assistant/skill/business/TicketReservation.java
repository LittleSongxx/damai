package org.javaup.ai.assistant.skill.business;

import java.util.Date;

public record TicketReservation(
        String reservationId,
        Date expiresAt,
        boolean locked,
        String message,
        String reservationStatus,
        String confirmedOrderNumber,
        String failureCategory,
        boolean retriable,
        boolean unknownResult,
        Long nextCheckAfterMs
) {
    public TicketReservation(String reservationId, Date expiresAt, boolean locked, String message) {
        this(reservationId, expiresAt, locked, message, locked ? "RESERVED" : "", null, null, false, false, 0L);
    }
}
