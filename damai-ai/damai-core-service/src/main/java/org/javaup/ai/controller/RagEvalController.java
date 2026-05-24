package org.javaup.ai.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.entity.AiRagEvalCase;
import org.javaup.ai.entity.AiRagEvalRun;
import org.javaup.ai.mapper.AiRagEvalCaseMapper;
import org.javaup.ai.service.RagEvalService;
import org.javaup.ai.service.HybridSearchService;
import org.javaup.ai.vo.RagEvalCaseRequest;
import org.javaup.ai.vo.RagEvalRunRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/rag-eval")
@RequiredArgsConstructor
public class RagEvalController {

    private final RagEvalService ragEvalService;
    private final AiRagEvalCaseMapper caseMapper;
    private final HybridSearchService hybridSearchService;

    @GetMapping("/debug-es")
    public ResponseEntity<Map<String, Object>> debugEs(@RequestParam(defaultValue = "如何申请退票？") String query) {
        var result = hybridSearchService.sparseSearch(query, 5);
        return ResponseEntity.ok(Map.of("code", 0, "query", query, "hitCount", result.size(), "hits", result.stream().map(r -> Map.of("chunkId", r.getChunkId(), "score", r.getScore(), "title", r.getTitle() != null ? r.getTitle() : "")).toList()));
    }

    @PostMapping("/preview")
    public ResponseEntity<Map<String, Object>> previewEval(@RequestBody(required = false) RagEvalRunRequest request) {
        return ResponseEntity.ok(Map.of("code", 0, "data", ragEvalService.previewEvaluation(request)));
    }

    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> startEval(@RequestBody(required = false) RagEvalRunRequest request) {
        AiRagEvalRun run = ragEvalService.startEvaluation(request);
        return ResponseEntity.ok(Map.of("code", 0, "evalRunId", run.getEvalRunId(), "totalCases", run.getTotalCases()));
    }

    @GetMapping("/status/{evalRunId}")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable String evalRunId) {
        AiRagEvalRun run = ragEvalService.getRunStatus(evalRunId);
        if (run == null) {
            return ResponseEntity.ok(Map.of("code", 1, "message", "not found"));
        }
        return ResponseEntity.ok(Map.of(
                "code", 0,
                "data", run,
                "qualityGate", ragEvalService.buildQualityGate(run)
        ));
    }

    @GetMapping("/results/{evalRunId}")
    public ResponseEntity<Map<String, Object>> getResults(@PathVariable String evalRunId) {
        return ResponseEntity.ok(Map.of("code", 0, "data", ragEvalService.listRunResults(evalRunId)));
    }

    @PostMapping("/cases/{caseId}/diagnose")
    public ResponseEntity<Map<String, Object>> diagnoseCase(@PathVariable String caseId,
                                                            @RequestBody(required = false) RagEvalRunRequest request) {
        Map<String, Object> diagnosis = ragEvalService.diagnoseCase(caseId, request);
        if (diagnosis == null) {
            return ResponseEntity.ok(Map.of("code", 1, "message", "not found"));
        }
        return ResponseEntity.ok(Map.of("code", 0, "data", diagnosis));
    }

    // --- Eval Case CRUD ---

    @GetMapping("/cases")
    public ResponseEntity<Map<String, Object>> listCases(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String difficulty) {
        LambdaQueryWrapper<AiRagEvalCase> wrapper = new LambdaQueryWrapper<AiRagEvalCase>()
                .eq(AiRagEvalCase::getStatus, 1);
        if (StringUtils.hasText(category)) {
            wrapper.eq(AiRagEvalCase::getCategory, category);
        }
        if (StringUtils.hasText(difficulty)) {
            wrapper.eq(AiRagEvalCase::getDifficulty, difficulty);
        }
        wrapper.orderByDesc(AiRagEvalCase::getCreateTime);
        List<AiRagEvalCase> cases = caseMapper.selectList(wrapper);
        return ResponseEntity.ok(Map.of("code", 0, "data", cases));
    }

    @GetMapping("/cases/{caseId}")
    public ResponseEntity<Map<String, Object>> getCase(@PathVariable String caseId) {
        AiRagEvalCase evalCase = caseMapper.selectOne(
                new LambdaQueryWrapper<AiRagEvalCase>().eq(AiRagEvalCase::getCaseId, caseId));
        if (evalCase == null) {
            return ResponseEntity.ok(Map.of("code", 1, "message", "not found"));
        }
        return ResponseEntity.ok(Map.of("code", 0, "data", evalCase));
    }

    @PostMapping("/cases")
    public ResponseEntity<Map<String, Object>> createCase(@RequestBody RagEvalCaseRequest request) {
        AiRagEvalCase evalCase = new AiRagEvalCase();
        evalCase.setCaseId(UUID.randomUUID().toString().replace("-", ""));
        evalCase.setQuestion(request.getQuestion());
        evalCase.setExpectedAnswer(request.getExpectedAnswer());
        evalCase.setExpectedChunks(request.getExpectedChunks());
        evalCase.setCategory(request.getCategory());
        evalCase.setDifficulty(request.getDifficulty());
        evalCase.setStatus(1);
        evalCase.setCreateTime(new Date());
        evalCase.setEditTime(new Date());
        caseMapper.insert(evalCase);
        return ResponseEntity.ok(Map.of("code", 0, "data", evalCase));
    }

    @PutMapping("/cases/{caseId}")
    public ResponseEntity<Map<String, Object>> updateCase(@PathVariable String caseId,
                                                           @RequestBody RagEvalCaseRequest request) {
        AiRagEvalCase evalCase = caseMapper.selectOne(
                new LambdaQueryWrapper<AiRagEvalCase>().eq(AiRagEvalCase::getCaseId, caseId));
        if (evalCase == null) {
            return ResponseEntity.ok(Map.of("code", 1, "message", "not found"));
        }
        if (StringUtils.hasText(request.getQuestion())) evalCase.setQuestion(request.getQuestion());
        if (StringUtils.hasText(request.getExpectedAnswer())) evalCase.setExpectedAnswer(request.getExpectedAnswer());
        if (StringUtils.hasText(request.getExpectedChunks())) evalCase.setExpectedChunks(request.getExpectedChunks());
        if (StringUtils.hasText(request.getCategory())) evalCase.setCategory(request.getCategory());
        if (StringUtils.hasText(request.getDifficulty())) evalCase.setDifficulty(request.getDifficulty());
        evalCase.setEditTime(new Date());
        caseMapper.updateById(evalCase);
        return ResponseEntity.ok(Map.of("code", 0, "data", evalCase));
    }

    @DeleteMapping("/cases/{caseId}")
    public ResponseEntity<Map<String, Object>> deleteCase(@PathVariable String caseId) {
        AiRagEvalCase evalCase = caseMapper.selectOne(
                new LambdaQueryWrapper<AiRagEvalCase>().eq(AiRagEvalCase::getCaseId, caseId));
        if (evalCase == null) {
            return ResponseEntity.ok(Map.of("code", 1, "message", "not found"));
        }
        evalCase.setStatus(0);
        evalCase.setEditTime(new Date());
        caseMapper.updateById(evalCase);
        return ResponseEntity.ok(Map.of("code", 0, "message", "deleted"));
    }

    @PostMapping("/reindex")
    public ResponseEntity<Map<String, Object>> triggerReindex() {
        try {
            Map<String, Object> result = hybridSearchService.reindexAll();
            return ResponseEntity.ok(Map.of("code", 0, "result", result));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("code", 1, "error", e.getMessage()));
        }
    }

    @PostMapping("/cases/refresh-expected-chunks")
    public ResponseEntity<Map<String, Object>> refreshExpectedChunks() {
        List<AiRagEvalCase> cases = caseMapper.selectList(
                new LambdaQueryWrapper<AiRagEvalCase>().eq(AiRagEvalCase::getStatus, 1));
        int updated = 0;
        List<String> errors = new ArrayList<>();
        for (AiRagEvalCase evalCase : cases) {
            try {
                var searchResult = hybridSearchService.hybridSearchWithHyde(
                        evalCase.getQuestion(), 5, true);
                List<String> chunkIds = searchResult.getSources() != null
                        ? searchResult.getSources().stream()
                                .map(org.javaup.ai.vo.RagSourceVo::getChunkId)
                                .toList()
                        : List.of();
                if (!chunkIds.isEmpty()) {
                    evalCase.setExpectedChunks(chunkIds.toString());
                    evalCase.setEditTime(new Date());
                    caseMapper.updateById(evalCase);
                    updated++;
                }
            } catch (Exception e) {
                errors.add(evalCase.getCaseId() + ": " + e.getMessage());
            }
        }
        return ResponseEntity.ok(Map.of("code", 0, "updated", updated,
                "total", cases.size(), "errors", errors));
    }

    @PostMapping("/cases/batch")
    public ResponseEntity<Map<String, Object>> batchImport(@RequestBody List<RagEvalCaseRequest> requests) {
        int imported = 0;
        for (RagEvalCaseRequest req : requests) {
            try {
                AiRagEvalCase evalCase = new AiRagEvalCase();
                evalCase.setCaseId(UUID.randomUUID().toString().replace("-", ""));
                evalCase.setQuestion(req.getQuestion());
                evalCase.setExpectedAnswer(req.getExpectedAnswer());
                evalCase.setExpectedChunks(req.getExpectedChunks());
                evalCase.setCategory(req.getCategory());
                evalCase.setDifficulty(req.getDifficulty());
                evalCase.setStatus(1);
                evalCase.setCreateTime(new Date());
                evalCase.setEditTime(new Date());
                caseMapper.insert(evalCase);
                imported++;
            } catch (Exception ignored) {
            }
        }
        return ResponseEntity.ok(Map.of("code", 0, "imported", imported));
    }
}
