package org.javaup.ai.cotroller;

import com.alibaba.fastjson.JSON;
import org.javaup.ai.assistant.compat.LegacyAssistantCompatibilityService;
import org.javaup.ai.common.ApiResponse;
import org.javaup.ai.dto.ProgramOrderCreateDto;
import org.javaup.ai.entity.AiApproval;
import org.javaup.ai.entity.AiToolAudit;
import org.javaup.ai.entity.AiWorkflowRun;
import org.javaup.ai.service.HybridSearchService;
import org.javaup.ai.service.AiWorkflowService;
import org.javaup.ai.vo.CreateOrderVo;
import org.javaup.ai.vo.WorkflowDetailVo;
import org.javaup.ai.ai.function.call.OrderCall;
import org.javaup.ai.workflow.AiWorkflowStepStatus;
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

    private final AiWorkflowService workflowService;
    private final OrderCall orderCall;
    private final HybridSearchService hybridSearchService;
    private final LegacyAssistantCompatibilityService legacyCompatibilityService;

    public AiWorkflowController(AiWorkflowService workflowService,
                                OrderCall orderCall,
                                HybridSearchService hybridSearchService,
                                LegacyAssistantCompatibilityService legacyCompatibilityService) {
        this.workflowService = workflowService;
        this.orderCall = orderCall;
        this.hybridSearchService = hybridSearchService;
        this.legacyCompatibilityService = legacyCompatibilityService;
    }

    @GetMapping("/workflows/{runId}")
    public ApiResponse<WorkflowDetailVo> getWorkflow(@PathVariable("runId") String runId) {
        AiWorkflowRun run = workflowService.getRun(runId);
        if (run == null) {
            WorkflowDetailVo legacyWorkflow = legacyCompatibilityService.getLegacyWorkflow(runId);
            if (legacyWorkflow == null) {
                return ApiResponse.error("工作流不存在");
            }
            return ApiResponse.ok(legacyWorkflow);
        }
        return ApiResponse.ok(WorkflowDetailVo.builder()
                .run(run)
                .steps(workflowService.getSteps(runId))
                .pendingApproval(workflowService.getPendingApproval(runId))
                .build());
    }

    @PostMapping("/workflows/{runId}/approve")
    public ApiResponse<CreateOrderVo> approve(@PathVariable("runId") String runId) {
        AiApproval approval = workflowService.approve(runId);
        if (approval == null) {
            try {
                return ApiResponse.ok(legacyCompatibilityService.approveLegacy(runId));
            } catch (RuntimeException ex) {
                return ApiResponse.error(ex.getMessage());
            }
        }
        Map<String, Object> preview = JSON.parseObject(approval.getPreviewJson());
        ProgramOrderCreateDto orderCreateDto = JSON.parseObject(JSON.toJSONString(preview.get("programOrderCreateDto")), ProgramOrderCreateDto.class);
        String orderNumber = orderCall.createOrder(orderCreateDto);

        CreateOrderVo result = new CreateOrderVo();
        result.setOrderNumber(orderNumber);
        result.setOrderListAddress(ORDER_LIST_ADDRESS);

        workflowService.recordStep(runId, "CREATE_ORDER", AiWorkflowStepStatus.COMPLETED, preview, result, null);
        AiToolAudit toolAudit = new AiToolAudit();
        toolAudit.setRunId(runId);
        toolAudit.setChatId(approval.getChatId());
        toolAudit.setUserId(approval.getUserId());
        toolAudit.setToolName("approveCreateOrder");
        toolAudit.setToolType("business");
        toolAudit.setRequestSummary(JSON.toJSONString(preview));
        toolAudit.setResponseSummary(JSON.toJSONString(result));
        toolAudit.setSuccess(true);
        workflowService.saveToolAudit(toolAudit);
        workflowService.markCompleted(runId, "COMPLETE", "订单已创建");
        return ApiResponse.ok(result);
    }

    @PostMapping("/workflows/{runId}/reject")
    public ApiResponse<Void> reject(@PathVariable("runId") String runId) {
        AiApproval approval = workflowService.reject(runId);
        if (approval == null) {
            try {
                legacyCompatibilityService.rejectLegacy(runId);
                return ApiResponse.ok();
            } catch (RuntimeException ex) {
                return ApiResponse.error(ex.getMessage());
            }
        }
        workflowService.recordStep(runId, "WAIT_APPROVAL", AiWorkflowStepStatus.FAILED, null, null, "用户拒绝审批");
        return ApiResponse.ok();
    }

    @PostMapping("/rag/reindex")
    public ApiResponse<Map<String, Object>> reindexFaq() {
        return ApiResponse.ok(hybridSearchService.reindexAll());
    }
}
