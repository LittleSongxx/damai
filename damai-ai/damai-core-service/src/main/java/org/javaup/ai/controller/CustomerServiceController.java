package org.javaup.ai.controller;

import lombok.RequiredArgsConstructor;
import org.javaup.ai.common.ApiResponse;
import org.javaup.ai.context.AiRequestContextHolder;
import org.javaup.ai.context.AiUserContext;
import org.javaup.ai.dto.CustomerEscalationRequest;
import org.javaup.ai.dto.CustomerQuickAnswerRequest;
import org.javaup.ai.entity.EscalationTicket;
import org.javaup.ai.service.CustomerHotQuestionService;
import org.javaup.ai.service.CustomerServiceMetricsService;
import org.javaup.ai.service.EscalationService;
import org.javaup.ai.vo.CustomerHotQuestionVo;
import org.javaup.ai.vo.CustomerQuickAnswerResponse;
import org.javaup.ai.vo.CustomerServiceDashboardVo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class CustomerServiceController {

    private final CustomerHotQuestionService hotQuestionService;
    private final EscalationService escalationService;
    private final CustomerServiceMetricsService metricsService;

    @GetMapping("/assistant/customer-service/starter-prompts")
    public ApiResponse<List<CustomerHotQuestionVo>> starterPrompts() {
        return ApiResponse.ok(hotQuestionService.starterPrompts());
    }

    @PostMapping("/assistant/customer-service/quick-answer")
    public ApiResponse<CustomerQuickAnswerResponse> quickAnswer(@RequestBody CustomerQuickAnswerRequest request) {
        AiUserContext user = AiRequestContextHolder.getRequiredUser();
        return ApiResponse.ok(hotQuestionService.quickAnswer(request, user.getUserId()));
    }

    @PostMapping("/assistant/customer-service/escalations")
    public ApiResponse<EscalationTicket> createEscalation(@RequestBody CustomerEscalationRequest request) {
        AiUserContext user = AiRequestContextHolder.getRequiredUser();
        EscalationTicket ticket = escalationService.createCustomerEscalation(request, user.getUserId());
        metricsService.record(request.getRunId(), request.getConversationId(), user.getUserId(),
                CustomerServiceMetricsService.ESCALATION_CREATED, 1D, null,
                Map.of("scene", "customer_service",
                        "intentCode", request.getIntentCode() == null ? "" : request.getIntentCode(),
                        "sentiment", request.getSentiment() == null ? "" : request.getSentiment()));
        return ApiResponse.ok(ticket);
    }

    @GetMapping("/assistant/customer-service/escalations/{ticketId}")
    public ApiResponse<EscalationTicket> getEscalation(@PathVariable String ticketId) {
        AiUserContext user = AiRequestContextHolder.getRequiredUser();
        EscalationTicket ticket = escalationService.getUserTicket(ticketId, user.getUserId());
        return ticket == null ? ApiResponse.error("工单不存在") : ApiResponse.ok(ticket);
    }

    @GetMapping("/assistant/admin/customer-service/dashboard")
    public ApiResponse<CustomerServiceDashboardVo> dashboard() {
        return ApiResponse.ok(metricsService.dashboard());
    }

    @GetMapping("/assistant/admin/customer-service/top-questions")
    public ApiResponse<List<Map<String, Object>>> topQuestions() {
        return ApiResponse.ok(metricsService.topQuestions());
    }

    @GetMapping("/assistant/admin/customer-service/unresolved-cases")
    public ApiResponse<List<Map<String, Object>>> unresolvedCases() {
        return ApiResponse.ok(metricsService.unresolvedCases());
    }
}
