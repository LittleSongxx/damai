package org.javaup.ai.assistant.skill.business;

import java.util.Date;

public record TicketReservation(
        String reservationId,
        Date expiresAt,
        boolean locked,
        String message
) {
}
