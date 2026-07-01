package org.javaup.ai.controller;

import lombok.RequiredArgsConstructor;
import org.javaup.ai.common.ApiResponse;
import org.javaup.ai.context.AiRequestContextHolder;
import org.javaup.ai.entity.EscalationTicket;
import org.javaup.ai.service.EscalationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/escalations")
@RequiredArgsConstructor
public class EscalationController {

    private final EscalationService escalationService;

    @GetMapping
    public ApiResponse<List<EscalationTicket>> listPending(@RequestParam(required = false) String priority,
                                                           @RequestParam(required = false) String sentiment,
                                                           @RequestParam(required = false) String intentCode) {
        return ApiResponse.ok(escalationService.getPendingTickets(priority, sentiment, intentCode));
    }

    @GetMapping("/user/{userId}")
    public ApiResponse<List<EscalationTicket>> getUserTickets(@PathVariable Long userId) {
        return ApiResponse.ok(escalationService.getUserTickets(userId));
    }

    @PostMapping("/{ticketId}/assign")
    public ApiResponse<EscalationTicket> assign(@PathVariable String ticketId) {
        Long agentId = AiRequestContextHolder.getRequiredUser().getUserId();
        return ApiResponse.ok(escalationService.assignTicket(ticketId, agentId));
    }

    @PostMapping("/{ticketId}/resolve")
    public ApiResponse<EscalationTicket> resolve(@PathVariable String ticketId, @RequestBody Map<String, String> body) {
        Long agentId = AiRequestContextHolder.getRequiredUser().getUserId();
        return ApiResponse.ok(escalationService.resolveTicket(ticketId, body.get("resolution"), agentId));
    }
}
