package org.javaup.ai.controller;

import lombok.RequiredArgsConstructor;
import org.javaup.ai.common.ApiResponse;
import org.javaup.ai.context.AiRequestContextHolder;
import org.javaup.ai.context.AiUserContext;
import org.javaup.ai.dto.CustomerHandoffRequest;
import org.javaup.ai.dto.CustomerQuickAnswerRequest;
import org.javaup.ai.entity.CustomerWorkItem;
import org.javaup.ai.service.CustomerHotQuestionService;
import org.javaup.ai.service.CustomerServiceMetricsService;
import org.javaup.ai.service.CustomerWorkItemService;
import org.javaup.ai.vo.CustomerHotQuestionVo;
import org.javaup.ai.vo.CustomerQuickAnswerResponse;
import org.javaup.ai.vo.CustomerServiceDashboardVo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
public class CustomerServiceController {

    private final CustomerHotQuestionService hotQuestionService;
    private final CustomerWorkItemService workItemService;
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

    @PostMapping("/assistant/customer-service/handoff")
    public ApiResponse<CustomerWorkItem> handoff(@RequestBody CustomerHandoffRequest request) {
        AiUserContext user = AiRequestContextHolder.getRequiredUser();
        CustomerWorkItem item = workItemService.handoff(request, user.getUserId());
        metricsService.record(request.getRunId(), request.getConversationId(), user.getUserId(),
                CustomerServiceMetricsService.WORK_ITEM_CREATED, 1D, null,
                Map.of("scene", "customer_service",
                        "intentCode", request.getIntentCode() == null ? "" : request.getIntentCode(),
                        "sentiment", request.getSentiment() == null ? "" : request.getSentiment()));
        return ApiResponse.ok(item);
    }

    @GetMapping("/assistant/customer-service/work-items/{workItemId}")
    public ApiResponse<CustomerWorkItem> getWorkItem(@PathVariable String workItemId) {
        AiUserContext user = AiRequestContextHolder.getRequiredUser();
        CustomerWorkItem item = workItemService.getUserWorkItem(workItemId, user.getUserId());
        return item == null ? ApiResponse.error("工单不存在") : ApiResponse.ok(item);
    }

    @PostMapping("/assistant/customer-service/work-items/{workItemId}/satisfaction")
    public ApiResponse<CustomerWorkItem> satisfaction(@PathVariable String workItemId,
                                                      @RequestBody Map<String, Integer> body) {
        AiUserContext user = AiRequestContextHolder.getRequiredUser();
        return ApiResponse.ok(workItemService.satisfaction(workItemId, body.get("score"), user.getUserId()));
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

    @GetMapping("/assistant/admin/customer-service/work-items")
    public ApiResponse<List<CustomerWorkItem>> listWorkItems(@RequestParam(required = false) String priority,
                                                             @RequestParam(required = false) String workStatus,
                                                             @RequestParam(required = false) String skillGroup) {
        return ApiResponse.ok(workItemService.list(priority, workStatus, skillGroup));
    }

    @GetMapping("/assistant/admin/customer-service/work-items/user/{userId}")
    public ApiResponse<List<CustomerWorkItem>> userWorkItems(@PathVariable Long userId) {
        return ApiResponse.ok(workItemService.userWorkItems(userId));
    }

    @PostMapping("/assistant/admin/customer-service/work-items/{workItemId}/assign")
    public ApiResponse<CustomerWorkItem> assign(@PathVariable String workItemId) {
        Long agentId = AiRequestContextHolder.getRequiredUser().getUserId();
        return ApiResponse.ok(workItemService.assign(workItemId, agentId));
    }

    @PostMapping("/assistant/admin/customer-service/work-items/{workItemId}/takeover")
    public ApiResponse<CustomerWorkItem> takeover(@PathVariable String workItemId) {
        Long agentId = AiRequestContextHolder.getRequiredUser().getUserId();
        return ApiResponse.ok(workItemService.takeover(workItemId, agentId));
    }

    @PostMapping("/assistant/admin/customer-service/work-items/{workItemId}/transfer")
    public ApiResponse<CustomerWorkItem> transfer(@PathVariable String workItemId, @RequestBody Map<String, String> body) {
        Long agentId = body.get("assignedTo") == null
                ? AiRequestContextHolder.getRequiredUser().getUserId()
                : Long.valueOf(body.get("assignedTo"));
        return ApiResponse.ok(workItemService.transfer(workItemId, agentId, body.get("skillGroup")));
    }

    @PostMapping("/assistant/admin/customer-service/work-items/{workItemId}/resolve")
    public ApiResponse<CustomerWorkItem> resolve(@PathVariable String workItemId, @RequestBody Map<String, String> body) {
        Long agentId = AiRequestContextHolder.getRequiredUser().getUserId();
        return ApiResponse.ok(workItemService.resolve(workItemId, body.get("conclusion"), agentId));
    }

    @PostMapping("/assistant/admin/customer-service/work-items/{workItemId}/close")
    public ApiResponse<CustomerWorkItem> close(@PathVariable String workItemId) {
        Long agentId = AiRequestContextHolder.getRequiredUser().getUserId();
        return ApiResponse.ok(workItemService.close(workItemId, agentId));
    }
}
