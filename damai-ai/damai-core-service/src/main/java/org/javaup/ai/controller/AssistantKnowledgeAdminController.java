package org.javaup.ai.controller;

import lombok.RequiredArgsConstructor;
import org.javaup.ai.assistant.mq.RagIngestionMessage;
import org.javaup.ai.assistant.mq.RagIngestionPublisher;
import org.javaup.ai.common.ApiResponse;
import org.javaup.ai.entity.RagIngestionTask;
import org.javaup.ai.service.DocumentIngestionService;
import org.javaup.ai.service.DocumentLifecycleService;
import org.javaup.ai.service.IngestionQualityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/assistant/admin/knowledge")
public class AssistantKnowledgeAdminController {

    private final DocumentLifecycleService documentLifecycleService;
    private final DocumentIngestionService documentIngestionService;
    private final IngestionQualityService ingestionQualityService;
    private final RagIngestionPublisher ragIngestionPublisher;

    @GetMapping("/reindex-jobs/{taskId}")
    public ApiResponse<RagIngestionTask> getKnowledgeReindexJob(@PathVariable("taskId") String taskId) {
        RagIngestionTask task = documentLifecycleService.getTask(taskId);
        return task == null ? ApiResponse.error("任务不存在或尚未被消费者领取") : ApiResponse.ok(task);
    }

    @PostMapping("/reindex")
    public ApiResponse<Map<String, Object>> reindexKnowledge() {
        return ApiResponse.ok(documentIngestionService.reindexAll());
    }

    @PostMapping("/reindex-jobs")
    public ApiResponse<Map<String, String>> createKnowledgeReindexJob(
            @RequestParam(defaultValue = "full") String taskType) {
        String normalizedType = "incremental".equalsIgnoreCase(taskType) ? "incremental" : "full";
        String prefix = "incremental".equals(normalizedType) ? "incr_" : "ingest_";
        String taskId = prefix + UUID.randomUUID().toString().replace("-", "");
        documentLifecycleService.createSubmittedTask(taskId, normalizedType);
        ragIngestionPublisher.publish(RagIngestionMessage.builder()
                .taskId(taskId)
                .taskType(normalizedType)
                .build());
        return ApiResponse.ok(Map.of("taskId", taskId, "status", "submitted", "taskType", normalizedType));
    }

    @GetMapping("/ingestion/tasks")
    public ApiResponse<?> getKnowledgeIngestionTasks() {
        return ApiResponse.ok(documentLifecycleService.getRecentTasks());
    }

    @PostMapping("/quality-report")
    public ApiResponse<Map<String, Object>> runKnowledgeQualityReport() {
        return ApiResponse.ok(ingestionQualityService.runQualityReport());
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> getKnowledgeStats() {
        return ApiResponse.ok(documentLifecycleService.getDocumentStats());
    }

}
