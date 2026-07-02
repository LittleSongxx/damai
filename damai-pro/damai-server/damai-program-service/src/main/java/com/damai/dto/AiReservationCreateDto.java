package com.damai.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AiReservationCreateDto {

    @NotNull
    @Valid
    private ProgramOrderCreateDto orderCreate;

    private String idempotencyKey;

    private String sourceRunId;

    private String sourceActionId;

    private Integer ttlSeconds;
}
