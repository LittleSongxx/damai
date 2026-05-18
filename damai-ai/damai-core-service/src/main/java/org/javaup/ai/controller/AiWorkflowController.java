package org.javaup.ai.controller;

import org.javaup.ai.assistant.AssistantRuntimeService;
import org.javaup.ai.common.ApiResponse;
import org.javaup.ai.service.HybridSearchService;
import org.javaup.ai.vo.AssistantActionResultVo;
import org.javaup.ai.vo.AssistantRunDetailVo;
import org.javaup.ai.vo.CreateOrderVo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.javaup.ai.constants.DaMaiConstant.ORDER_LIST_ADDRESS;

@RestController
@RequestMapping("/ai")
public class AiWorkflowController {

    private final AssistantRuntimeService assistantRuntimeService;
    private final HybridSearchService hybridSearchService;

    public AiWorkflowController(AssistantRuntimeService assistantRuntimeService,
                                HybridSearchService hybridSearchService) {
        this.assistantRuntimeService = assistantRuntimeService;
        this.hybridSearchService = hybridSearchService;
    }

    @GetMapping("/workflows/{runId}")
    public ApiResponse<AssistantRunDetailVo> getWorkflow(@PathVariable("runId") String runId) {
        AssistantRunDetailVo detail = assistantRuntimeService.getRunDetail(runId);
        if (detail == null || detail.getRun() == null) {
            return ApiResponse.error("工作流不存在");
        }
        return ApiResponse.ok(detail);
    }

    @PostMapping("/workflows/{runId}/approve")
    public ApiResponse<CreateOrderVo> approve(@PathVariable("runId") String runId) {
        AssistantRunDetailVo detail = assistantRuntimeService.getRunDetail(runId);
        if (detail == null || detail.getRun() == null || detail.getPendingAction() == null) {
            return ApiResponse.error("没有待审批的操作");
        }
        AssistantActionResultVo resultVo = assistantRuntimeService.approveAction(runId, detail.getPendingAction().getActionId());
        CreateOrderVo result = new CreateOrderVo();
        result.setOrderNumber(resultVo.getOrderNumber());
        result.setOrderListAddress(ORDER_LIST_ADDRESS);
        return ApiResponse.ok(result);
    }

    @PostMapping("/workflows/{runId}/reject")
    public ApiResponse<Void> reject(@PathVariable("runId") String runId) {
        AssistantRunDetailVo detail = assistantRuntimeService.getRunDetail(runId);
        if (detail == null || detail.getRun() == null || detail.getPendingAction() == null) {
            return ApiResponse.error("没有待审批的操作");
        }
        assistantRuntimeService.rejectAction(runId, detail.getPendingAction().getActionId());
        return ApiResponse.ok();
    }

    @PostMapping("/rag/reindex")
    public ApiResponse<Map<String, Object>> reindexFaq() {
        return ApiResponse.ok(hybridSearchService.reindexAll());
    }
}
