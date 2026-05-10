package org.javaup.ai.cotroller;

import lombok.RequiredArgsConstructor;
import org.javaup.ai.entity.AiRagEvalRun;
import org.javaup.ai.service.RagEvalService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/rag-eval")
@RequiredArgsConstructor
public class RagEvalController {

    private final RagEvalService ragEvalService;

    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> startEval() {
        AiRagEvalRun run = ragEvalService.startEvaluation();
        return ResponseEntity.ok(Map.of("code", 0, "evalRunId", run.getEvalRunId(), "totalCases", run.getTotalCases()));
    }

    @GetMapping("/status/{evalRunId}")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable String evalRunId) {
        AiRagEvalRun run = ragEvalService.getRunStatus(evalRunId);
        if (run == null) {
            return ResponseEntity.ok(Map.of("code", 1, "message", "not found"));
        }
        return ResponseEntity.ok(Map.of("code", 0, "data", run));
    }
}
