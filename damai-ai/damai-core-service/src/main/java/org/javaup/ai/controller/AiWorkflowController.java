package org.javaup.ai.controller;

import org.javaup.ai.assistant.AssistantRuntimeService;
import org.javaup.ai.assistant.mq.RagIngestionMessage;
import org.javaup.ai.assistant.mq.RagIngestionPublisher;
import org.javaup.ai.common.ApiResponse;
import org.javaup.ai.service.DocumentIngestionService;
import org.javaup.ai.service.DocumentLifecycleService;
import org.javaup.ai.service.HybridSearchService;
import org.javaup.ai.service.IngestionQualityService;
import org.javaup.ai.entity.RagIngestionTask;
import org.javaup.ai.vo.AssistantActionResultVo;
import org.javaup.ai.vo.AssistantRunDetailVo;
import org.javaup.ai.vo.CreateOrderVo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static org.javaup.ai.constants.DaMaiConstant.ORDER_LIST_ADDRESS;

@RestController
@RequestMapping("/ai")
public class AiWorkflowController {

    private final AssistantRuntimeService assistantRuntimeService;
    private final HybridSearchService hybridSearchService;
    private final DocumentIngestionService documentIngestionService;
    private final DocumentLifecycleService documentLifecycleService;
    private final IngestionQualityService ingestionQualityService;
    private final RagIngestionPublisher ragIngestionPublisher;

    public AiWorkflowController(AssistantRuntimeService assistantRuntimeService,
                                 HybridSearchService hybridSearchService,
                                 DocumentIngestionService documentIngestionService,
                                 DocumentLifecycleService documentLifecycleService,
                                 IngestionQualityService ingestionQualityService,
                                 RagIngestionPublisher ragIngestionPublisher) {
        this.assistantRuntimeService = assistantRuntimeService;
        this.hybridSearchService = hybridSearchService;
        this.documentIngestionService = documentIngestionService;
        this.documentLifecycleService = documentLifecycleService;
        this.ingestionQualityService = ingestionQualityService;
        this.ragIngestionPublisher = ragIngestionPublisher;
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
        AssistantActionResultVo resultVo = assistantRuntimeService.approveAction(runId,
                detail.getPendingAction().getActionId());
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

    // ======================== RAG Ingestion Endpoints ========================

    /** Full reindex — synchronous */
    @PostMapping("/rag/reindex")
    public ApiResponse<Map<String, Object>> reindexFaq() {
        return ApiResponse.ok(hybridSearchService.reindexAll());
    }

    /** Full reindex — async via MQ */
    @PostMapping("/rag/reindex/async")
    public ApiResponse<Map<String, String>> reindexFaqAsync() {
        String taskId = "ingest_" + java.util.UUID.randomUUID().toString().replace("-", "");
        RagIngestionMessage msg =
                RagIngestionMessage.builder()
                        .taskId(taskId)
                        .taskType("full")
                        .build();
        documentLifecycleService.createSubmittedTask(taskId, "full");
        ragIngestionPublisher.publish(msg);
        return ApiResponse.ok(Map.of("taskId", taskId, "status", "submitted"));
    }

    @PostMapping("/rag/reindex-jobs")
    public ApiResponse<Map<String, String>> createReindexJob(@RequestParam(defaultValue = "full") String taskType) {
        String normalizedType = "incremental".equalsIgnoreCase(taskType) ? "incremental" : "full";
        String prefix = "incremental".equals(normalizedType) ? "incr_" : "ingest_";
        String taskId = prefix + java.util.UUID.randomUUID().toString().replace("-", "");
        documentLifecycleService.createSubmittedTask(taskId, normalizedType);
        ragIngestionPublisher.publish(RagIngestionMessage.builder()
                .taskId(taskId)
                .taskType(normalizedType)
                .build());
        return ApiResponse.ok(Map.of("taskId", taskId, "status", "submitted", "taskType", normalizedType));
    }

    @GetMapping("/rag/reindex-jobs/{taskId}")
    public ApiResponse<RagIngestionTask> getReindexJob(@PathVariable("taskId") String taskId) {
        RagIngestionTask task = documentLifecycleService.getTask(taskId);
        return task == null ? ApiResponse.error("任务不存在或尚未被消费者领取") : ApiResponse.ok(task);
    }

    /** Incremental reindex */
    @PostMapping("/rag/reindex/incremental")
    public ApiResponse<Map<String, Object>> incrementalReindex() {
        return ApiResponse.ok(hybridSearchService.incrementalReindex());
    }

    /** Get ingestion task status */
    @GetMapping("/rag/ingestion/tasks")
    public ApiResponse<?> getIngestionTasks() {
        return ApiResponse.ok(documentLifecycleService.getRecentTasks());
    }

    /** Run quality report */
    @PostMapping("/rag/quality-report")
    public ApiResponse<Map<String, Object>> runQualityReport() {
        return ApiResponse.ok(ingestionQualityService.runQualityReport());
    }

    /** Get document statistics */
    @GetMapping("/rag/stats")
    public ApiResponse<Map<String, Object>> getRagStats() {
        return ApiResponse.ok(documentLifecycleService.getDocumentStats());
    }

    /** Publish a document */
    @PostMapping("/rag/documents/{docId}/publish")
    public ApiResponse<?> publishDocument(@PathVariable Long docId) {
        var doc = documentLifecycleService.publishDocument(docId);
        return doc != null ? ApiResponse.ok(doc) : ApiResponse.error("文档不存在");
    }

    /** Archive a document */
    @PostMapping("/rag/documents/{docId}/archive")
    public ApiResponse<?> archiveDocument(@PathVariable Long docId) {
        var doc = documentLifecycleService.archiveDocument(docId);
        return doc != null ? ApiResponse.ok(doc) : ApiResponse.error("文档不存在");
    }
}
