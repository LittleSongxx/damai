package com.damai.controller;

import com.damai.common.ApiResponse;
import com.damai.dto.AiReservationConfirmDto;
import com.damai.dto.AiReservationCreateDto;
import com.damai.dto.AiReservationReleaseDto;
import com.damai.service.AiReservationService;
import com.damai.service.InternalAccessGuard;
import com.damai.vo.AiReservationVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/program/ai/reservations")
public class AiReservationController {

    private final AiReservationService aiReservationService;
    private final InternalAccessGuard internalAccessGuard;

    @PostMapping
    public ApiResponse<AiReservationVo> reserve(@Valid @RequestBody AiReservationCreateDto request,
                                                @RequestHeader(value = "X-Internal-Token", required = false) String internalToken,
                                                @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {
        internalAccessGuard.require(internalToken);
        if (request.getIdempotencyKey() == null || request.getIdempotencyKey().isBlank()) {
            request.setIdempotencyKey(idempotencyKey);
        }
        return ApiResponse.ok(aiReservationService.reserve(request));
    }

    @PostMapping("/{reservationId}/confirm")
    public ApiResponse<AiReservationVo> confirm(@PathVariable String reservationId,
                                                @RequestBody(required = false) AiReservationConfirmDto request,
                                                @RequestHeader(value = "X-Internal-Token", required = false) String internalToken,
                                                @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {
        internalAccessGuard.require(internalToken);
        String key = request == null || request.getIdempotencyKey() == null || request.getIdempotencyKey().isBlank()
                ? idempotencyKey
                : request.getIdempotencyKey();
        return ApiResponse.ok(aiReservationService.confirm(reservationId, key));
    }

    @PostMapping("/{reservationId}/release")
    public ApiResponse<AiReservationVo> release(@PathVariable String reservationId,
                                                @RequestHeader(value = "X-Internal-Token", required = false) String internalToken,
                                                @RequestBody(required = false) AiReservationReleaseDto request) {
        internalAccessGuard.require(internalToken);
        String reason = request == null ? null : request.getReason();
        return ApiResponse.ok(aiReservationService.release(reservationId, reason));
    }

    @GetMapping("/{reservationId}")
    public ApiResponse<AiReservationVo> status(@PathVariable String reservationId,
                                               @RequestHeader(value = "X-Internal-Token", required = false) String internalToken) {
        internalAccessGuard.require(internalToken);
        return ApiResponse.ok(aiReservationService.status(reservationId));
    }
}
