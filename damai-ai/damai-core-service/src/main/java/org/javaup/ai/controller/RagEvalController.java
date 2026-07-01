package org.javaup.ai.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.entity.AiRagBadCase;
import org.javaup.ai.entity.AiRagEvalCase;
import org.javaup.ai.entity.AiRagEvalDataset;
import org.javaup.ai.entity.AiRagEvalJudgeConfig;
import org.javaup.ai.entity.AiRagEvalRetrievalConfig;
import org.javaup.ai.entity.AiRagEvalRun;
import org.javaup.ai.entity.AiRagOnlineTrace;
import org.javaup.ai.context.AiRequestContextHolder;
import org.javaup.ai.mapper.AiRagEvalCaseMapper;
import org.javaup.ai.mapper.AiRagEvalDatasetMapper;
import org.javaup.ai.mapper.AiRagEvalJudgeConfigMapper;
import org.javaup.ai.mapper.AiRagEvalRetrievalConfigMapper;
import org.javaup.ai.service.HybridSearchService;
import org.javaup.ai.service.RagBadCaseService;
import org.javaup.ai.service.RagEvalBaselineService;
import org.javaup.ai.service.RagEvalReportService;
import org.javaup.ai.service.RagEvalService;
import org.javaup.ai.service.RagOnlineTraceService;
import org.javaup.ai.vo.RagBadCaseConvertRequest;
import org.javaup.ai.vo.RagBadCaseRequest;
import org.javaup.ai.vo.RagBadCaseReviewRequest;
import org.javaup.ai.vo.RagEvalCaseRequest;
import org.javaup.ai.vo.RagEvalRunRequest;
import org.javaup.ai.vo.RagOnlineTraceRequest;
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
    private final AiRagEvalDatasetMapper datasetMapper;
    private final AiRagEvalRetrievalConfigMapper retrievalConfigMapper;
    private final AiRagEvalJudgeConfigMapper judgeConfigMapper;
    private final HybridSearchService hybridSearchService;
    private final RagEvalReportService ragEvalReportService;
    private final RagEvalBaselineService ragEvalBaselineService;
    private final RagOnlineTraceService ragOnlineTraceService;
    private final RagBadCaseService ragBadCaseService;

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

    @GetMapping("/runs/{evalRunId}/report")
    public ResponseEntity<Map<String, Object>> getRunReport(@PathVariable String evalRunId) {
        Map<String, Object> report = ragEvalReportService.getRunReport(evalRunId);
        if (report == null) {
            return ResponseEntity.ok(Map.of("code", 1, "message", "not found"));
        }
        return ResponseEntity.ok(Map.of("code", 0, "data", report));
    }

    @GetMapping("/runs/{evalRunId}/compare")
    public ResponseEntity<Map<String, Object>> compareRun(@PathVariable String evalRunId,
                                                          @RequestParam String baselineRunId) {
        Map<String, Object> comparison = ragEvalBaselineService.compareRun(evalRunId, baselineRunId);
        if (comparison == null) {
            return ResponseEntity.ok(Map.of("code", 1, "message", "not found"));
        }
        return ResponseEntity.ok(Map.of("code", 0, "data", comparison));
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
            @RequestParam(required = false) String difficulty,
            @RequestParam(required = false) String datasetId,
            @RequestParam(required = false) String datasetVersion,
            @RequestParam(required = false) String caseType) {
        LambdaQueryWrapper<AiRagEvalCase> wrapper = new LambdaQueryWrapper<AiRagEvalCase>()
                .eq(AiRagEvalCase::getStatus, 1);
        if (StringUtils.hasText(datasetId)) {
            wrapper.eq(AiRagEvalCase::getDatasetId, datasetId);
        }
        if (StringUtils.hasText(datasetVersion)) {
            wrapper.eq(AiRagEvalCase::getDatasetVersion, datasetVersion);
        }
        if (StringUtils.hasText(category)) {
            wrapper.eq(AiRagEvalCase::getCategory, category);
        }
        if (StringUtils.hasText(difficulty)) {
            wrapper.eq(AiRagEvalCase::getDifficulty, difficulty);
        }
        if (StringUtils.hasText(caseType)) {
            wrapper.eq(AiRagEvalCase::getCaseType, caseType);
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
        evalCase.setDatasetId(StringUtils.hasText(request.getDatasetId()) ? request.getDatasetId() : "default-golden");
        evalCase.setDatasetVersion(StringUtils.hasText(request.getDatasetVersion()) ? request.getDatasetVersion() : "v1");
        evalCase.setCaseType(StringUtils.hasText(request.getCaseType()) ? request.getCaseType() : "single_hop");
        evalCase.setTags(request.getTags());
        evalCase.setRequiredFacts(request.getRequiredFacts());
        evalCase.setForbiddenFacts(request.getForbiddenFacts());
        evalCase.setExpectedCitations(request.getExpectedCitations());
        evalCase.setReviewStatus(StringUtils.hasText(request.getReviewStatus()) ? request.getReviewStatus() : "APPROVED");
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
        if (StringUtils.hasText(request.getDatasetId())) evalCase.setDatasetId(request.getDatasetId());
        if (StringUtils.hasText(request.getDatasetVersion())) evalCase.setDatasetVersion(request.getDatasetVersion());
        if (StringUtils.hasText(request.getCaseType())) evalCase.setCaseType(request.getCaseType());
        if (StringUtils.hasText(request.getTags())) evalCase.setTags(request.getTags());
        if (StringUtils.hasText(request.getRequiredFacts())) evalCase.setRequiredFacts(request.getRequiredFacts());
        if (StringUtils.hasText(request.getForbiddenFacts())) evalCase.setForbiddenFacts(request.getForbiddenFacts());
        if (StringUtils.hasText(request.getExpectedCitations())) evalCase.setExpectedCitations(request.getExpectedCitations());
        if (StringUtils.hasText(request.getReviewStatus())) evalCase.setReviewStatus(request.getReviewStatus());
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
                evalCase.setDatasetId(StringUtils.hasText(req.getDatasetId()) ? req.getDatasetId() : "default-golden");
                evalCase.setDatasetVersion(StringUtils.hasText(req.getDatasetVersion()) ? req.getDatasetVersion() : "v1");
                evalCase.setCaseType(StringUtils.hasText(req.getCaseType()) ? req.getCaseType() : "single_hop");
                evalCase.setTags(req.getTags());
                evalCase.setRequiredFacts(req.getRequiredFacts());
                evalCase.setForbiddenFacts(req.getForbiddenFacts());
                evalCase.setExpectedCitations(req.getExpectedCitations());
                evalCase.setReviewStatus(StringUtils.hasText(req.getReviewStatus()) ? req.getReviewStatus() : "APPROVED");
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

    @GetMapping("/datasets")
    public ResponseEntity<Map<String, Object>> listDatasets() {
        return ResponseEntity.ok(Map.of("code", 0, "data", datasetMapper.selectList(
                new LambdaQueryWrapper<AiRagEvalDataset>().eq(AiRagEvalDataset::getStatus, 1)
                        .orderByDesc(AiRagEvalDataset::getCreateTime))));
    }

    @PostMapping("/datasets")
    public ResponseEntity<Map<String, Object>> createDataset(@RequestBody AiRagEvalDataset dataset) {
        dataset.setCreateTime(new Date());
        dataset.setEditTime(new Date());
        dataset.setStatus(1);
        datasetMapper.insert(dataset);
        return ResponseEntity.ok(Map.of("code", 0, "data", dataset));
    }

    @GetMapping("/retrieval-configs")
    public ResponseEntity<Map<String, Object>> listRetrievalConfigs() {
        return ResponseEntity.ok(Map.of("code", 0, "data", retrievalConfigMapper.selectList(
                new LambdaQueryWrapper<AiRagEvalRetrievalConfig>().eq(AiRagEvalRetrievalConfig::getStatus, 1)
                        .orderByDesc(AiRagEvalRetrievalConfig::getCreateTime))));
    }

    @PostMapping("/retrieval-configs")
    public ResponseEntity<Map<String, Object>> createRetrievalConfig(@RequestBody AiRagEvalRetrievalConfig config) {
        config.setCreateTime(new Date());
        config.setEditTime(new Date());
        config.setStatus(1);
        retrievalConfigMapper.insert(config);
        return ResponseEntity.ok(Map.of("code", 0, "data", config));
    }

    @GetMapping("/judge-configs")
    public ResponseEntity<Map<String, Object>> listJudgeConfigs() {
        return ResponseEntity.ok(Map.of("code", 0, "data", judgeConfigMapper.selectList(
                new LambdaQueryWrapper<AiRagEvalJudgeConfig>().eq(AiRagEvalJudgeConfig::getStatus, 1)
                        .orderByDesc(AiRagEvalJudgeConfig::getCreateTime))));
    }

    @PostMapping("/judge-configs")
    public ResponseEntity<Map<String, Object>> createJudgeConfig(@RequestBody AiRagEvalJudgeConfig config) {
        config.setCreateTime(new Date());
        config.setEditTime(new Date());
        config.setStatus(1);
        judgeConfigMapper.insert(config);
        return ResponseEntity.ok(Map.of("code", 0, "data", config));
    }

    @GetMapping("/traces")
    public ResponseEntity<Map<String, Object>> listTraces(@RequestParam(required = false) String conversationId,
                                                          @RequestParam(required = false) String feedbackType) {
        return ResponseEntity.ok(Map.of("code", 0, "data", ragOnlineTraceService.listTraces(conversationId, feedbackType)));
    }

    @GetMapping("/traces/{traceId}")
    public ResponseEntity<Map<String, Object>> getTrace(@PathVariable String traceId) {
        AiRagOnlineTrace trace = ragOnlineTraceService.getTrace(traceId);
        if (trace == null) {
            return ResponseEntity.ok(Map.of("code", 1, "message", "not found"));
        }
        return ResponseEntity.ok(Map.of("code", 0, "data", trace));
    }

    @PostMapping("/traces")
    public ResponseEntity<Map<String, Object>> createTrace(@RequestBody RagOnlineTraceRequest request) {
        return ResponseEntity.ok(Map.of("code", 0, "data", ragOnlineTraceService.createTrace(request)));
    }

    @GetMapping("/bad-cases")
    public ResponseEntity<Map<String, Object>> listBadCases(@RequestParam(required = false) String reviewStatus,
                                                            @RequestParam(required = false) String failureType) {
        return ResponseEntity.ok(Map.of("code", 0, "data", ragBadCaseService.listBadCases(reviewStatus, failureType)));
    }

    @GetMapping("/bad-cases/{badCaseId}")
    public ResponseEntity<Map<String, Object>> getBadCase(@PathVariable String badCaseId) {
        AiRagBadCase badCase = ragBadCaseService.getBadCase(badCaseId);
        if (badCase == null) {
            return ResponseEntity.ok(Map.of("code", 1, "message", "not found"));
        }
        return ResponseEntity.ok(Map.of("code", 0, "data", badCase));
    }

    @PostMapping("/bad-cases")
    public ResponseEntity<Map<String, Object>> createBadCase(@RequestBody RagBadCaseRequest request) {
        return ResponseEntity.ok(Map.of("code", 0, "data", ragBadCaseService.createBadCase(request)));
    }

    @PostMapping("/bad-cases/from-trace")
    public ResponseEntity<Map<String, Object>> createBadCaseFromTrace(@RequestParam String traceId,
                                                                      @RequestParam(required = false) String failureType,
                                                                      @RequestParam(required = false) String operatorNote) {
        AiRagBadCase badCase = ragBadCaseService.createFromTrace(traceId, failureType, operatorNote);
        if (badCase == null) {
            return ResponseEntity.ok(Map.of("code", 1, "message", "trace not found"));
        }
        return ResponseEntity.ok(Map.of("code", 0, "data", badCase));
    }

    @PostMapping("/bad-cases/{badCaseId}/convert")
    public ResponseEntity<Map<String, Object>> convertBadCase(@PathVariable String badCaseId,
                                                              @RequestBody RagBadCaseConvertRequest request) {
        AiRagEvalCase evalCase = ragBadCaseService.convertToEvalCase(badCaseId, request);
        if (evalCase == null) {
            return ResponseEntity.ok(Map.of("code", 1, "message", "bad case not found"));
        }
        return ResponseEntity.ok(Map.of("code", 0, "data", evalCase));
    }

    @PostMapping("/bad-cases/{badCaseId}/review")
    public ResponseEntity<Map<String, Object>> reviewBadCase(@PathVariable String badCaseId,
                                                             @RequestBody(required = false) RagBadCaseReviewRequest request) {
        Long reviewerId = AiRequestContextHolder.getOptional()
                .map(context -> context.getUser() == null ? null : context.getUser().getUserId())
                .orElse(null);
        AiRagBadCase badCase = ragBadCaseService.reviewBadCase(badCaseId, request, reviewerId);
        if (badCase == null) {
            return ResponseEntity.ok(Map.of("code", 1, "message", "bad case not found"));
        }
        return ResponseEntity.ok(Map.of("code", 0, "data", badCase));
    }
}
