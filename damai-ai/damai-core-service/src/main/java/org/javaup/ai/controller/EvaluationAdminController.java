package org.javaup.ai.controller;

import lombok.RequiredArgsConstructor;
import org.javaup.ai.common.ApiResponse;
import org.javaup.ai.service.EvaluationCenterService;
import org.javaup.ai.vo.EvaluationRunRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/assistant/admin/evals")
@RequiredArgsConstructor
public class EvaluationAdminController {

    private final EvaluationCenterService evaluationCenterService;

    @PostMapping("/runs")
    public ApiResponse<Map<String, Object>> startRun(@RequestBody EvaluationRunRequest request) {
        return ApiResponse.ok(evaluationCenterService.startRun(request));
    }

    @GetMapping("/runs/{runId}")
    public ApiResponse<Map<String, Object>> getRun(@PathVariable String runId) {
        return ApiResponse.ok(evaluationCenterService.getRun(runId));
    }

    @GetMapping("/runs/{runId}/results")
    public ApiResponse<Map<String, Object>> getRunResults(@PathVariable String runId) {
        return ApiResponse.ok(evaluationCenterService.getRunResults(runId));
    }

    @GetMapping("/dashboard")
    public ApiResponse<Map<String, Object>> dashboard() {
        return ApiResponse.ok(evaluationCenterService.dashboard());
    }

    @PostMapping("/runs/{runId}/replay-failed")
    public ApiResponse<Map<String, Object>> replayFailed(@PathVariable String runId) {
        return ApiResponse.ok(evaluationCenterService.replayFailed(runId));
    }
}
