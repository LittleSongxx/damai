package org.javaup.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.assistant.eval.RagEvalScorer;
import org.javaup.ai.assistant.skill.knowledge.KnowledgeRetrievalContext;
import org.javaup.ai.assistant.skill.knowledge.KnowledgeRetrievalOrchestrator;
import org.javaup.ai.assistant.skill.knowledge.KnowledgeRetrievalPlan;
import org.javaup.ai.assistant.skill.knowledge.KnowledgeRetrievalPlanner;
import org.javaup.ai.config.EvalConfig;
import org.javaup.ai.entity.AiRagEvalCase;
import org.javaup.ai.entity.AiRagEvalResult;
import org.javaup.ai.entity.AiRagEvalRun;
import org.javaup.ai.entity.RagChunk;
import org.javaup.ai.mapper.AiRagEvalCaseMapper;
import org.javaup.ai.mapper.AiRagEvalResultMapper;
import org.javaup.ai.mapper.AiRagEvalRunMapper;
import org.javaup.ai.mapper.RagChunkMapper;
import org.javaup.ai.vo.RagEvalRunRequest;
import org.javaup.ai.vo.RagSearchResultVo;
import org.javaup.ai.vo.RagSourceVo;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RagEvalService {

    private static final ExecutorService EVAL_WORKER_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "eval-case-worker");
        t.setDaemon(true);
        return t;
    });

    private static final ExecutorService EVAL_INNER_POOL = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "eval-case-inner");
        t.setDaemon(true);
        return t;
    });

    private static final int DEFAULT_EVAL_TOP_K = 5;
    private static final boolean DEFAULT_ENABLE_RERANK = true;
    private static final int MAX_EVAL_CONCURRENCY = 3;
    private static final int RUN_TIMEOUT_MINUTES = 30;
    private static final int STAGE_TIMEOUT_MINUTES = 10;

    private final AiRagEvalCaseMapper caseMapper;
    private final AiRagEvalRunMapper runMapper;
    private final AiRagEvalResultMapper resultMapper;
    private final HybridSearchService hybridSearchService;
    private final RagEvalScorer ragEvalScorer;
    private final RagChunkMapper ragChunkMapper;
    private final EvalConfig evalConfig;
    private ApplicationContext applicationContext;

    @Autowired
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    public RagEvalService(AiRagEvalCaseMapper caseMapper,
                           AiRagEvalRunMapper runMapper,
                           AiRagEvalResultMapper resultMapper,
                           HybridSearchService hybridSearchService,
                           RagEvalScorer ragEvalScorer,
                           RagChunkMapper ragChunkMapper,
                           EvalConfig evalConfig) {
        this.caseMapper = caseMapper;
        this.runMapper = runMapper;
        this.resultMapper = resultMapper;
        this.hybridSearchService = hybridSearchService;
        this.ragEvalScorer = ragEvalScorer;
        this.ragChunkMapper = ragChunkMapper;
        this.evalConfig = evalConfig;
    }

    public AiRagEvalRun startEvaluation() {
        return startEvaluation(null);
    }

    public AiRagEvalRun startEvaluation(RagEvalRunRequest request) {
        RagEvalRunRequest normalizedRequest = normalizeRequest(request);
        List<AiRagEvalCase> cases = loadEvalCases(normalizedRequest);
        if (cases.isEmpty()) {
            throw new RuntimeException("No eval cases found");
        }

        AiRagEvalRun evalRun = new AiRagEvalRun();
        evalRun.setEvalRunId(UUID.randomUUID().toString().replace("-", ""));
        evalRun.setTotalCases(cases.size());
        evalRun.setCompletedCases(0);
        evalRun.setRunStatus("RUNNING");
        evalRun.setCreateTime(new Date());
        evalRun.setEditTime(new Date());
        evalRun.setStatus(1);
        runMapper.insert(evalRun);

        RagEvalService target = applicationContext != null ? applicationContext.getBean(RagEvalService.class) : this;
        target.executeEvalAsync(evalRun, cases, normalizedRequest);
        return evalRun;
    }

    public Map<String, Object> previewEvaluation(RagEvalRunRequest request) {
        RagEvalRunRequest normalizedRequest = normalizeRequest(request);
        List<AiRagEvalCase> matchedCases = queryEvalCases(normalizedRequest);
        List<AiRagEvalCase> selectedCases = applyLimit(matchedCases, normalizedRequest.getLimit());
        List<String> requestedCaseIds = normalizedRequest.getCaseIds() != null ? normalizedRequest.getCaseIds() : List.of();
        java.util.Set<String> matchedCaseIds = matchedCases.stream()
                .map(AiRagEvalCase::getCaseId)
                .collect(Collectors.toSet());
        List<String> missingCaseIds = requestedCaseIds.stream()
                .filter(caseId -> !matchedCaseIds.contains(caseId))
                .toList();

        long missingExpectedChunks = selectedCases.stream()
                .filter(evalCase -> !StringUtils.hasText(evalCase.getExpectedChunks()))
                .count();
        long missingExpectedAnswer = selectedCases.stream()
                .filter(evalCase -> !StringUtils.hasText(evalCase.getExpectedAnswer()))
                .count();

        Map<String, Long> categoryBreakdown = selectedCases.stream()
                .collect(Collectors.groupingBy(
                        evalCase -> StringUtils.hasText(evalCase.getCategory()) ? evalCase.getCategory() : "UNSPECIFIED",
                        LinkedHashMap::new,
                        Collectors.counting()));
        Map<String, Long> difficultyBreakdown = selectedCases.stream()
                .collect(Collectors.groupingBy(
                        evalCase -> StringUtils.hasText(evalCase.getDifficulty()) ? evalCase.getDifficulty() : "UNSPECIFIED",
                        LinkedHashMap::new,
                        Collectors.counting()));

        List<String> warnings = new ArrayList<>();
        if (selectedCases.isEmpty()) {
            warnings.add("No active eval cases matched the current filters");
        }
        if (!missingCaseIds.isEmpty()) {
            warnings.add("Some requested caseIds were not found or not active: " + missingCaseIds);
        }
        if (missingExpectedChunks > 0) {
            warnings.add("Selected cases missing expectedChunks: " + missingExpectedChunks);
        }
        if (missingExpectedAnswer > 0) {
            warnings.add("Selected cases missing expectedAnswer: " + missingExpectedAnswer);
        }
        if (normalizedRequest.getLimit() != null && matchedCases.size() > selectedCases.size()) {
            warnings.add("Selection truncated by request limit=" + normalizedRequest.getLimit());
        }

        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("canRun", !selectedCases.isEmpty());
        preview.put("degradedMode", evalConfig.isDegradedMode());
        preview.put("selectedCases", selectedCases.size());
        preview.put("matchedCasesBeforeLimit", matchedCases.size());
        preview.put("estimatedLlmCalls", selectedCases.size() * 3L);
        preview.put("request", buildRequestSummary(normalizedRequest));
        preview.put("missingCaseIds", missingCaseIds);
        preview.put("categoryBreakdown", categoryBreakdown);
        preview.put("difficultyBreakdown", difficultyBreakdown);
        preview.put("warnings", warnings);
        preview.put("sampleCases", selectedCases.stream().limit(5).map(this::buildCasePreview).toList());
        return preview;
    }

    public List<AiRagEvalResult> listRunResults(String evalRunId) {
        return resultMapper.selectList(new LambdaQueryWrapper<AiRagEvalResult>()
                .eq(AiRagEvalResult::getEvalRunId, evalRunId)
                .orderByDesc(AiRagEvalResult::getId));
    }

    public Map<String, Object> diagnoseCase(String caseId, RagEvalRunRequest request) {
        if (!StringUtils.hasText(caseId)) {
            return null;
        }
        AiRagEvalCase evalCase = caseMapper.selectOne(new LambdaQueryWrapper<AiRagEvalCase>()
                .eq(AiRagEvalCase::getCaseId, caseId)
                .eq(AiRagEvalCase::getStatus, 1));
        if (evalCase == null) {
            return null;
        }

        RagEvalRunRequest normalizedRequest = normalizeRequest(request);
        List<String> expectedChunkIds = normalizeChunkIds(parseChunkIds(evalCase.getExpectedChunks()));
        EvalRetrievalSnapshot retrievalSnapshot = retrieveEvalEvidence(evalCase, normalizedRequest);
        List<String> retrievedChunkIds = normalizeChunkIds(retrievalSnapshot.chunkIds());
        List<String> retrievedAt5ChunkIds = retrievedChunkIds.stream().limit(5).toList();

        List<String> lookupChunkIds = new ArrayList<>(expectedChunkIds);
        lookupChunkIds.addAll(retrievedChunkIds);
        Map<String, RagChunk> chunkIndex = loadChunkIndex(lookupChunkIds);

        Set<String> expectedSet = new LinkedHashSet<>(expectedChunkIds);
        Set<String> retrievedSet = new LinkedHashSet<>(retrievedChunkIds);
        Set<String> retrievedAt5Set = new LinkedHashSet<>(retrievedAt5ChunkIds);

        List<String> missingExpectedChunkIds = expectedChunkIds.stream()
                .filter(chunkId -> !chunkIndex.containsKey(chunkId) || chunkIndex.get(chunkId) == null)
                .toList();
        List<String> matchedExpectedChunkIds = expectedChunkIds.stream()
                .filter(retrievedSet::contains)
                .toList();
        List<String> matchedAt5ChunkIds = expectedChunkIds.stream()
                .filter(retrievedAt5Set::contains)
                .toList();

        int activeExpectedChunkCount = expectedChunkIds.size() - missingExpectedChunkIds.size();
        List<String> warnings = buildDiagnosisWarnings(expectedChunkIds, missingExpectedChunkIds, matchedAt5ChunkIds, retrievedChunkIds);

        Map<String, Object> diagnosis = new LinkedHashMap<>();
        diagnosis.put("caseId", evalCase.getCaseId());
        diagnosis.put("question", evalCase.getQuestion());
        diagnosis.put("category", evalCase.getCategory());
        diagnosis.put("difficulty", evalCase.getDifficulty());
        diagnosis.put("request", buildRequestSummary(normalizedRequest));
        diagnosis.put("retrievalPath", retrievalSnapshot.retrievalPath());
        diagnosis.put("expectedChunkCount", expectedChunkIds.size());
        diagnosis.put("activeExpectedChunkCount", activeExpectedChunkCount);
        diagnosis.put("missingExpectedChunkCount", missingExpectedChunkIds.size());
        diagnosis.put("retrievedChunkCount", retrievedChunkIds.size());
        diagnosis.put("overlapCount", matchedExpectedChunkIds.size());
        diagnosis.put("overlapAt5Count", matchedAt5ChunkIds.size());
        diagnosis.put("recallAt5", calculateRecallAtK(retrievedChunkIds, expectedChunkIds, 5));
        diagnosis.put("precisionAt5", calculatePrecisionAtK(retrievedChunkIds, expectedChunkIds, 5));
        diagnosis.put("mrr", calculateMrr(retrievedChunkIds, expectedChunkIds));
        diagnosis.put("ndcgAt5", calculateNdcgGraded(retrievedChunkIds, parseChunkWeights(evalCase.getExpectedChunks()), 5));
        diagnosis.put("missingExpectedChunkIds", missingExpectedChunkIds);
        diagnosis.put("matchedExpectedChunkIds", matchedExpectedChunkIds);
        diagnosis.put("matchedAt5ChunkIds", matchedAt5ChunkIds);
        diagnosis.put("expectedChunks", buildChunkDiagnostics(expectedChunkIds, chunkIndex, retrievedAt5Set));
        diagnosis.put("retrievedChunks", buildChunkDiagnostics(retrievedChunkIds, chunkIndex, expectedSet));
        diagnosis.put("suspectedRootCause", diagnoseRootCause(
                expectedChunkIds.size(),
                missingExpectedChunkIds.size(),
                activeExpectedChunkCount,
                matchedAt5ChunkIds.size(),
                retrievedChunkIds.size()));
        diagnosis.put("warnings", warnings);
        return diagnosis;
    }

    private Map<String, Object> buildRequestSummary(RagEvalRunRequest request) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("category", request.getCategory());
        summary.put("difficulty", request.getDifficulty());
        summary.put("caseIds", request.getCaseIds());
        summary.put("limit", request.getLimit());
        summary.put("topK", request.getTopK());
        summary.put("enableRerank", request.getEnableRerank());
        return summary;
    }

    private Map<String, Object> buildCasePreview(AiRagEvalCase evalCase) {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("caseId", evalCase.getCaseId());
        preview.put("question", evalCase.getQuestion());
        preview.put("category", evalCase.getCategory());
        preview.put("difficulty", evalCase.getDifficulty());
        preview.put("hasExpectedChunks", StringUtils.hasText(evalCase.getExpectedChunks()));
        preview.put("hasExpectedAnswer", StringUtils.hasText(evalCase.getExpectedAnswer()));
        return preview;
    }

    private List<String> normalizeChunkIds(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return List.of();
        }
        return chunkIds.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private Map<String, RagChunk> loadChunkIndex(List<String> chunkIds) {
        Map<String, RagChunk> chunkIndex = new LinkedHashMap<>();
        if (ragChunkMapper == null || chunkIds == null || chunkIds.isEmpty()) {
            return chunkIndex;
        }
        for (String chunkId : normalizeChunkIds(chunkIds)) {
            chunkIndex.put(chunkId, ragChunkMapper.selectByChunkUid(chunkId));
        }
        return chunkIndex;
    }

    private List<Map<String, Object>> buildChunkDiagnostics(List<String> chunkIds,
                                                            Map<String, RagChunk> chunkIndex,
                                                            Set<String> matchedChunkIds) {
        List<Map<String, Object>> diagnostics = new ArrayList<>();
        for (String chunkId : normalizeChunkIds(chunkIds)) {
            RagChunk chunk = chunkIndex.get(chunkId);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("chunkId", chunkId);
            detail.put("exists", chunk != null);
            detail.put("matched", matchedChunkIds != null && matchedChunkIds.contains(chunkId));
            if (chunk != null) {
                detail.put("docId", chunk.getDocId());
                detail.put("chunkType", chunk.getChunkType());
                detail.put("headingPath", chunk.getHeadingPath());
                detail.put("question", chunk.getQuestion());
                detail.put("textPreview", abbreviate(chunk.getText(), 160));
            }
            diagnostics.add(detail);
        }
        return diagnostics;
    }

    private List<String> buildDiagnosisWarnings(List<String> expectedChunkIds,
                                                List<String> missingExpectedChunkIds,
                                                List<String> matchedAt5ChunkIds,
                                                List<String> retrievedChunkIds) {
        List<String> warnings = new ArrayList<>();
        if (expectedChunkIds.isEmpty()) {
            warnings.add("Case has no expectedChunks labels");
        }
        if (!missingExpectedChunkIds.isEmpty()) {
            warnings.add("Some expectedChunks are missing from active chunk storage: " + missingExpectedChunkIds);
        }
        if (!expectedChunkIds.isEmpty() && matchedAt5ChunkIds.isEmpty() && !retrievedChunkIds.isEmpty()) {
            warnings.add("Current retrieval top5 has zero overlap with expectedChunks");
        }
        if (retrievedChunkIds.isEmpty()) {
            warnings.add("Current retrieval returned no chunks");
        }
        return warnings;
    }

    private String diagnoseRootCause(int expectedChunkCount,
                                     int missingExpectedChunkCount,
                                     int activeExpectedChunkCount,
                                     int overlapAt5Count,
                                     int retrievedChunkCount) {
        if (expectedChunkCount == 0) {
            return "NO_EXPECTED_CHUNKS";
        }
        if (missingExpectedChunkCount == expectedChunkCount) {
            return "ALL_EXPECTED_CHUNKS_MISSING";
        }
        if (activeExpectedChunkCount > 0 && overlapAt5Count == 0 && retrievedChunkCount > 0) {
            return "ZERO_OVERLAP_WITH_CURRENT_RETRIEVAL";
        }
        if (missingExpectedChunkCount > 0) {
            return "SOME_EXPECTED_CHUNKS_MISSING";
        }
        if (overlapAt5Count < activeExpectedChunkCount) {
            return "PARTIAL_OVERLAP_WITH_CURRENT_RETRIEVAL";
        }
        return "ALIGNED";
    }

    private String abbreviate(String text, int maxLength) {
        if (!StringUtils.hasText(text) || maxLength <= 0 || text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }

    private RagEvalRunRequest normalizeRequest(RagEvalRunRequest request) {
        RagEvalRunRequest normalized = new RagEvalRunRequest();
        if (request == null) {
            normalized.setCaseIds(List.of());
            normalized.setTopK(DEFAULT_EVAL_TOP_K);
            normalized.setEnableRerank(DEFAULT_ENABLE_RERANK);
            return normalized;
        }

        normalized.setCategory(StringUtils.hasText(request.getCategory()) ? request.getCategory().trim() : null);
        normalized.setDifficulty(StringUtils.hasText(request.getDifficulty()) ? request.getDifficulty().trim() : null);
        normalized.setCaseIds(request.getCaseIds() == null ? List.of() : request.getCaseIds().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList());
        normalized.setLimit(request.getLimit() != null && request.getLimit() > 0 ? request.getLimit() : null);
        normalized.setTopK(request.getTopK() != null && request.getTopK() > 0 ? request.getTopK() : DEFAULT_EVAL_TOP_K);
        normalized.setEnableRerank(request.getEnableRerank() != null ? request.getEnableRerank() : DEFAULT_ENABLE_RERANK);
        return normalized;
    }

    private List<AiRagEvalCase> loadEvalCases(RagEvalRunRequest request) {
        return applyLimit(queryEvalCases(request), request.getLimit());
    }

    private List<AiRagEvalCase> applyLimit(List<AiRagEvalCase> cases, Integer limit) {
        if (limit == null || limit <= 0 || cases.size() <= limit) {
            return cases;
        }
        return new ArrayList<>(cases.subList(0, limit));
    }

    private List<AiRagEvalCase> queryEvalCases(RagEvalRunRequest request) {
        LambdaQueryWrapper<AiRagEvalCase> wrapper = new LambdaQueryWrapper<AiRagEvalCase>()
                .eq(AiRagEvalCase::getStatus, 1);
        if (StringUtils.hasText(request.getCategory())) {
            wrapper.eq(AiRagEvalCase::getCategory, request.getCategory());
        }
        if (StringUtils.hasText(request.getDifficulty())) {
            wrapper.eq(AiRagEvalCase::getDifficulty, request.getDifficulty());
        }
        if (request.getCaseIds() != null && !request.getCaseIds().isEmpty()) {
            wrapper.in(AiRagEvalCase::getCaseId, request.getCaseIds());
        }
        wrapper.orderByDesc(AiRagEvalCase::getCreateTime);
        return caseMapper.selectList(wrapper);
    }

    @Async("aiTraceExecutor")
    public void executeEvalAsync(AiRagEvalRun evalRun, List<AiRagEvalCase> cases, RagEvalRunRequest request) {
        // Bounded concurrency: max 3 cases in parallel to avoid overwhelming DeepSeek API
        int maxConcurrency = MAX_EVAL_CONCURRENCY;
        Semaphore semaphore = new Semaphore(maxConcurrency);

        ExecutorService executor = EVAL_WORKER_POOL;

        AtomicInteger completed = new AtomicInteger(0);
        AtomicReference<Double> totalRecall = new AtomicReference<>(0.0);
        AtomicReference<Double> totalPrecision = new AtomicReference<>(0.0);
        AtomicReference<Double> totalMrr = new AtomicReference<>(0.0);
        AtomicReference<Double> totalNdcg = new AtomicReference<>(0.0);
        AtomicReference<Double> totalHitRate = new AtomicReference<>(0.0);
        AtomicReference<Double> totalCtxPrecision = new AtomicReference<>(0.0);
        AtomicReference<Double> totalCtxRecall = new AtomicReference<>(0.0);
        AtomicReference<Double> totalCtxRelevance = new AtomicReference<>(0.0);
        AtomicReference<Double> totalFaithfulness = new AtomicReference<>(0.0);
        AtomicReference<Double> totalAnswerRelevancy = new AtomicReference<>(0.0);
        AtomicReference<Double> totalAnswerCorrectness = new AtomicReference<>(0.0);
        AtomicInteger retrievalEvalCount = new AtomicInteger(0);
        AtomicInteger generationEvalCount = new AtomicInteger(0);
        ConcurrentLinkedQueue<String> caseErrors = new ConcurrentLinkedQueue<>();

        // Submit all cases in parallel, bounded by semaphore
        List<Future<?>> futures = new ArrayList<>();
        for (AiRagEvalCase evalCase : cases) {
            Future<?> future = executor.submit(() -> {
                boolean acquired = false;
                try {
                    semaphore.acquire();
                    acquired = true;
                    evaluateSingleCase(evalRun, evalCase,
                            request,
                            completed, totalRecall, totalPrecision, totalMrr, totalNdcg, totalHitRate,
                            totalCtxPrecision, totalCtxRecall, totalCtxRelevance,
                            totalFaithfulness, totalAnswerRelevancy, totalAnswerCorrectness,
                            retrievalEvalCount, generationEvalCount);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    caseErrors.add("caseId=" + evalCase.getCaseId() + ", error=interrupted");
                    log.error("Eval case interrupted: caseId={}", evalCase.getCaseId());
                } catch (Exception e) {
                    caseErrors.add(buildCaseError(evalCase.getCaseId(), e));
                    log.error("Eval case failed: caseId={}", evalCase.getCaseId(), e);
                } finally {
                    if (acquired) {
                        semaphore.release();
                    }
                }
            });
            futures.add(future);
        }

        // Wait for all cases to complete
        long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(RUN_TIMEOUT_MINUTES);
        for (int i = 0; i < futures.size(); i++) {
            Future<?> future = futures.get(i);
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                log.error("Global eval timeout: cancelling remaining cases ({}/{})", i, futures.size());
                caseErrors.add("global timeout before case index=" + i);
                for (int j = i; j < futures.size(); j++) {
                    futures.get(j).cancel(true);
                }
                break;
            }
            try {
                future.get(remaining, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                log.error("Global eval timeout waiting for case index={}", i);
                caseErrors.add("global timeout waiting for case index=" + i);
                future.cancel(true);
            } catch (Exception e) {
                caseErrors.add("future index=" + i + ", error=" + e.getClass().getSimpleName());
                log.error("Eval case future failed at index={}: {}", i, e.toString());
            }
        }

        int c = completed.get();
        evalRun.setCompletedCases(c);
        evalRun.setAvgRecall(c > 0 ? totalRecall.get() / c : 0);
        evalRun.setAvgPrecision(c > 0 ? totalPrecision.get() / c : 0);
        evalRun.setAvgHitRate(c > 0 ? totalHitRate.get() / c : 0);
        evalRun.setAvgMrr(c > 0 ? totalMrr.get() / c : 0);
        evalRun.setAvgNdcg(c > 0 ? totalNdcg.get() / c : 0);
        if (retrievalEvalCount.get() > 0) {
            evalRun.setAvgCtxPrecision(totalCtxPrecision.get() / retrievalEvalCount.get());
            evalRun.setAvgCtxRecall(totalCtxRecall.get() / retrievalEvalCount.get());
            evalRun.setAvgContextRelevance(totalCtxRelevance.get() / retrievalEvalCount.get());
        }
        if (generationEvalCount.get() > 0) {
            evalRun.setAvgFaithfulness(totalFaithfulness.get() / generationEvalCount.get());
            evalRun.setAvgAnswerRelevancy(totalAnswerRelevancy.get() / generationEvalCount.get());
            evalRun.setAvgAnswerCorrectness(totalAnswerCorrectness.get() / generationEvalCount.get());
        }
        String runErrorMessage = buildRunErrorMessage(cases.size(), c, caseErrors);
        if (StringUtils.hasText(runErrorMessage)) {
            evalRun.setErrorMessage(runErrorMessage);
            evalRun.setRunStatus(c == 0 ? "FAILED" : "COMPLETED_WITH_ERRORS");
        } else {
            evalRun.setErrorMessage(null);
            evalRun.setRunStatus("COMPLETED");
        }
        evalRun.setEditTime(new Date());
        runMapper.updateById(evalRun);
        Map<String, Object> qualityGate = buildQualityGate(evalRun);
        log.info("RAG eval finished: evalRunId={}, status={}, cases={}, avgRecall={}, avgPrecision={}, avgHitRate={}, avgMRR={}, avgNDCG={}, " +
                        "avgCtxPrecision={}, avgCtxRecall={}, avgCtxRelevance={}, " +
                        "avgFaithfulness={}, avgAnswerRelevancy={}, avgAnswerCorrectness={}, qualityGate={}, errorMessage={}",
                evalRun.getEvalRunId(), evalRun.getRunStatus(), c,
                evalRun.getAvgRecall(), evalRun.getAvgPrecision(), evalRun.getAvgHitRate(),
                evalRun.getAvgMrr(), evalRun.getAvgNdcg(),
                evalRun.getAvgCtxPrecision(), evalRun.getAvgCtxRecall(), evalRun.getAvgContextRelevance(),
                evalRun.getAvgFaithfulness(), evalRun.getAvgAnswerRelevancy(), evalRun.getAvgAnswerCorrectness(),
                qualityGate.get("status"), evalRun.getErrorMessage());
    }

    private void evaluateSingleCase(AiRagEvalRun evalRun, AiRagEvalCase evalCase,
                                     RagEvalRunRequest request,
                                     AtomicInteger completed,
                                     AtomicReference<Double> totalRecall, AtomicReference<Double> totalPrecision,
                                     AtomicReference<Double> totalMrr, AtomicReference<Double> totalNdcg,
                                     AtomicReference<Double> totalHitRate,
                                     AtomicReference<Double> totalCtxPrecision,
                                     AtomicReference<Double> totalCtxRecall,
                                     AtomicReference<Double> totalCtxRelevance,
                                     AtomicReference<Double> totalFaithfulness,
                                     AtomicReference<Double> totalAnswerRelevancy,
                                     AtomicReference<Double> totalAnswerCorrectness,
                                     AtomicInteger retrievalEvalCount, AtomicInteger generationEvalCount) {
        long start = System.currentTimeMillis();

        // ---- Step 1: 检索 ----
        final List<String> retrievedChunks;
        final List<Document> retrievedDocs;

        EvalRetrievalSnapshot retrievalSnapshot = retrieveEvalEvidence(evalCase, request);
        retrievedChunks = retrievalSnapshot.chunkIds();
        retrievedDocs = retrievalSnapshot.documents();
        long latency = System.currentTimeMillis() - start;

        // ---- Step 2: 传统检索指标 ----
        List<String> expectedChunks = parseChunkIds(evalCase.getExpectedChunks());
        Map<String, Integer> chunkWeights = parseChunkWeights(evalCase.getExpectedChunks());
        double recall = calculateRecallAtK(retrievedChunks, expectedChunks, 5);
        double precision = calculatePrecisionAtK(retrievedChunks, expectedChunks, 5);
        double mrr = calculateMrr(retrievedChunks, expectedChunks);
        double ndcg = calculateNdcgGraded(retrievedChunks, chunkWeights, 5);
        double hitRate = expectedChunks.isEmpty() ? 1.0 :
                (retrievedChunks.stream().limit(5).anyMatch(expectedChunks::contains) ? 1.0 : 0.0);

        AiRagEvalResult result = new AiRagEvalResult();
        result.setEvalRunId(evalRun.getEvalRunId());
        result.setCaseId(evalCase.getCaseId());
        result.setQuestion(evalCase.getQuestion());
        result.setRetrievedChunks(String.join(",", retrievedChunks));
        result.setRecallAt5(recall);
        result.setMrr(mrr);
        result.setNdcgAt5(ndcg);
        result.setLatencyMs(latency);
        double contextPrecision = 0;
        double contextRecall = 0;
        double contextRelevance = 0;
        boolean hasContextEvaluation = false;
        double faithfulness = 0;
        double answerRelevancy = 0;
        double answerCorrectness = 0;
        boolean hasGenerationEvaluation = false;

        // ---- Step 3 + 4 in parallel: generateAnswer + evaluateContext + chunkRelevance (independent) ----
        Future<String> answerFuture = EVAL_INNER_POOL.submit(() ->
                ragEvalScorer.generateAnswer(evalCase.getQuestion(), retrievedDocs));
        Future<RagEvalScorer.ContextEvalResult> ctxFuture = EVAL_INNER_POOL.submit(() -> {
            try {
                return ragEvalScorer.evaluateContext(
                        evalCase.getQuestion(),
                        evalCase.getExpectedAnswer(),
                        retrievedDocs);
            } catch (Exception e) {
                log.warn("Context evaluation failed for caseId={}: {}", evalCase.getCaseId(), e.getMessage());
                return new RagEvalScorer.ContextEvalResult(0, 0, 0);
            }
        });
        Future<Map<Integer, Integer>> chunkRelFuture = EVAL_INNER_POOL.submit(() -> {
            try {
                List<String> chunkTexts = retrievedDocs.stream()
                        .map(d -> d.getContent() != null ? d.getContent() : "")
                        .toList();
                return ragEvalScorer.evaluateChunkRelevance(
                        evalCase.getQuestion(), evalCase.getExpectedAnswer(), chunkTexts);
            } catch (Exception e) {
                log.warn("Chunk relevance grading failed for caseId={}: {}", evalCase.getCaseId(), e.getMessage());
                return Map.of();
            }
        });

        String generatedAnswer;
        try {
            generatedAnswer = answerFuture.get(STAGE_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            log.error("Answer generation TIMEOUT for caseId={}", evalCase.getCaseId());
            answerFuture.cancel(true);
            generatedAnswer = "（生成超时）";
        } catch (Exception e) {
            log.error("Answer generation failed for caseId={}: {}", evalCase.getCaseId(), e.getMessage());
            generatedAnswer = "（生成失败）";
        }
        result.setGeneratedAnswer(generatedAnswer);

        // Collect context eval result
        try {
            RagEvalScorer.ContextEvalResult ctxResult = ctxFuture.get(STAGE_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            contextPrecision = ctxResult.contextPrecision();
            contextRecall = ctxResult.contextRecall();
            contextRelevance = ctxResult.contextRelevance();
            result.setContextPrecision(contextPrecision);
            result.setContextRecall(contextRecall);
            result.setContextRelevance(contextRelevance);
            hasContextEvaluation = true;
        } catch (TimeoutException e) {
            log.error("Context eval TIMEOUT for caseId={}", evalCase.getCaseId());
            ctxFuture.cancel(true);
        } catch (Exception e) {
            log.warn("Context evaluation failed for caseId={}: {}", evalCase.getCaseId(), e.getMessage());
        }

        // Merge LLM chunk relevance grades with human-annotated weights and recompute NDCG
        try {
            Map<Integer, Integer> llmGrades = chunkRelFuture.get(STAGE_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!llmGrades.isEmpty()) {
                Map<String, Integer> mergedWeights = new LinkedHashMap<>(chunkWeights);
                for (int i = 0; i < retrievedChunks.size(); i++) {
                    Integer llmGrade = llmGrades.get(i);
                    if (llmGrade != null && llmGrade > 0) {
                        String chunkId = retrievedChunks.get(i);
                        // LLM grade complements or overrides human annotation
                        mergedWeights.putIfAbsent(chunkId, llmGrade);
                    }
                }
                ndcg = calculateNdcgGraded(retrievedChunks, mergedWeights, 5);
                result.setNdcgAt5(ndcg);
            }
        } catch (TimeoutException e) {
            log.warn("Chunk relevance grading TIMEOUT for caseId={}", evalCase.getCaseId());
            chunkRelFuture.cancel(true);
        } catch (Exception e) {
            log.warn("Chunk relevance grading failed for caseId={}: {}", evalCase.getCaseId(), e.getMessage());
        }

        // ---- Step 5: 生成质量评估（依赖 generatedAnswer） ----
        try {
            RagEvalScorer.GenEvalResult genResult = ragEvalScorer.evaluateGeneration(
                    evalCase.getQuestion(),
                    generatedAnswer,
                    evalCase.getExpectedAnswer(),
                    retrievedDocs);
            faithfulness = genResult.faithfulness();
            answerRelevancy = genResult.answerRelevancy();
            answerCorrectness = genResult.answerCorrectness();
            result.setFaithfulnessScore(faithfulness);
            result.setAnswerRelevancyScore(answerRelevancy);
            result.setAnswerCorrectnessScore(answerCorrectness);
            hasGenerationEvaluation = true;
        } catch (Exception e) {
            log.warn("Generation evaluation failed for caseId={}: {}", evalCase.getCaseId(), e.getMessage());
        }

        result.setEvalMethod(buildEvalMethod(request));
        result.setCreateTime(new Date());
        result.setEditTime(new Date());
        result.setStatus(1);
        resultMapper.insert(result);
        final double persistedContextPrecision = contextPrecision;
        final double persistedContextRecall = contextRecall;
        final double persistedContextRelevance = contextRelevance;
        final double persistedFaithfulness = faithfulness;
        final double persistedAnswerRelevancy = answerRelevancy;
        final double persistedAnswerCorrectness = answerCorrectness;
        totalRecall.updateAndGet(v -> v + recall);
        totalPrecision.updateAndGet(v -> v + precision);
        totalMrr.updateAndGet(v -> v + mrr);
        totalNdcg.updateAndGet(v -> v + ndcg);
        totalHitRate.updateAndGet(v -> v + hitRate);
        if (hasContextEvaluation) {
            totalCtxPrecision.updateAndGet(v -> v + persistedContextPrecision);
            totalCtxRecall.updateAndGet(v -> v + persistedContextRecall);
            totalCtxRelevance.updateAndGet(v -> v + persistedContextRelevance);
            retrievalEvalCount.incrementAndGet();
        }
        if (hasGenerationEvaluation) {
            totalFaithfulness.updateAndGet(v -> v + persistedFaithfulness);
            totalAnswerRelevancy.updateAndGet(v -> v + persistedAnswerRelevancy);
            totalAnswerCorrectness.updateAndGet(v -> v + persistedAnswerCorrectness);
            generationEvalCount.incrementAndGet();
        }
        completed.incrementAndGet();
        log.info("Eval case completed: caseId={}, recall={}, mrr={}, ndcg={}, ctxPrecision={}, ctxRecall={}, faithfulness={}, answerRelevancy={}, answerCorrectness={}",
                evalCase.getCaseId(), recall, mrr, ndcg,
                result.getContextPrecision(), result.getContextRecall(),
                result.getFaithfulnessScore(), result.getAnswerRelevancyScore(), result.getAnswerCorrectnessScore());
    }

    private String buildEvalMethod(RagEvalRunRequest request) {
        int topK = request != null && request.getTopK() != null && request.getTopK() > 0
                ? request.getTopK()
                : DEFAULT_EVAL_TOP_K;
        boolean rerank = request == null || request.getEnableRerank() == null
                ? DEFAULT_ENABLE_RERANK
                : request.getEnableRerank();
        return "RAGAS_LLM_JUDGE_K" + topK + (rerank ? "_RR" : "_NO_RR");
    }

    private String buildCaseError(String caseId, Throwable error) {
        Throwable rootCause = error;
        while (rootCause.getCause() != null) {
            rootCause = rootCause.getCause();
        }
        String message = StringUtils.hasText(rootCause.getMessage())
                ? rootCause.getMessage()
                : rootCause.getClass().getSimpleName();
        if (message.length() > 180) {
            message = message.substring(0, 180);
        }
        return "caseId=" + caseId + ", error=" + message;
    }

    private String buildRunErrorMessage(int totalCases, int completedCases, ConcurrentLinkedQueue<String> caseErrors) {
        if (caseErrors.isEmpty() && completedCases >= totalCases) {
            return null;
        }
        String prefix = "Completed " + completedCases + "/" + totalCases + " eval cases";
        if (caseErrors.isEmpty()) {
            return prefix;
        }
        String details = caseErrors.stream()
                .filter(StringUtils::hasText)
                .distinct()
                .limit(5)
                .collect(Collectors.joining(" | "));
        return prefix + "; " + details;
    }

    private EvalRetrievalSnapshot retrieveEvalEvidence(AiRagEvalCase evalCase, RagEvalRunRequest request) {
        int normalizedTopK = request != null && request.getTopK() != null && request.getTopK() > 0
                ? request.getTopK()
                : DEFAULT_EVAL_TOP_K;
        boolean normalizedEnableRerank = request == null || request.getEnableRerank() == null
                ? DEFAULT_ENABLE_RERANK
                : request.getEnableRerank();
        if (evalConfig.isDegradedMode()) {
            List<String> chunkIds = parseChunkIds(evalCase.getExpectedChunks());
            return new EvalRetrievalSnapshot(chunkIds, resolveDocuments(chunkIds), "degraded_expected_chunks");
        }

        KnowledgeRetrievalPlanner retrievalPlanner = applicationContext != null
                ? applicationContext.getBeanProvider(KnowledgeRetrievalPlanner.class).getIfAvailable()
                : null;
        KnowledgeRetrievalOrchestrator retrievalOrchestrator = applicationContext != null
                ? applicationContext.getBeanProvider(KnowledgeRetrievalOrchestrator.class).getIfAvailable()
                : null;

        if (retrievalPlanner != null && retrievalOrchestrator != null) {
            try {
                KnowledgeRetrievalPlan basePlan = retrievalPlanner.plan(evalCase.getQuestion());
                KnowledgeRetrievalPlan evalPlan = new KnowledgeRetrievalPlan(
                        basePlan.originalQuery(),
                        basePlan.normalizedQuery(),
                        normalizedTopK,
                        normalizedEnableRerank,
                        basePlan.evidenceSourceLimit(),
                        basePlan.evidenceSnippetLimit(),
                        basePlan.evidenceContextCharBudget(),
                        basePlan.subQuestions(),
                        basePlan.complexity());
                KnowledgeRetrievalContext retrievalContext = retrievalOrchestrator.retrieve(evalPlan);
                RagSearchResultVo searchResult = retrievalContext != null ? retrievalContext.searchResult() : null;
                List<String> chunkIds = searchResult != null && searchResult.getSources() != null
                        ? searchResult.getSources().stream()
                        .map(RagSourceVo::getChunkId)
                        .filter(StringUtils::hasText)
                        .toList()
                        : List.of();
                List<Document> documents = retrievalContext != null
                        && retrievalContext.answerDocuments() != null
                        && !retrievalContext.answerDocuments().isEmpty()
                        ? retrievalContext.answerDocuments()
                        : searchResult != null && searchResult.getDocuments() != null
                        ? searchResult.getDocuments()
                        : List.of();
                if (!chunkIds.isEmpty() || !documents.isEmpty()) {
                    return new EvalRetrievalSnapshot(chunkIds, documents, "production_orchestrator");
                }
            } catch (Exception e) {
                log.warn("Production retrieval path failed for eval caseId={}, fallback to hybrid search: {}",
                        evalCase.getCaseId(), e.getMessage());
            }
        }

        RagSearchResultVo searchResult = hybridSearchService.hybridSearchWithTrace(
                evalCase.getQuestion(), normalizedTopK, normalizedEnableRerank);
        List<String> chunkIds = searchResult.getSources() != null
                ? searchResult.getSources().stream()
                .map(RagSourceVo::getChunkId)
                .filter(StringUtils::hasText)
                .toList()
                : List.of();
        List<Document> documents = searchResult.getDocuments() != null
                ? searchResult.getDocuments()
                : List.of();
        return new EvalRetrievalSnapshot(chunkIds, documents, "hybrid_search_with_trace_fallback");
    }

    private record EvalRetrievalSnapshot(List<String> chunkIds, List<Document> documents, String retrievalPath) {}

    private List<Document> resolveDocuments(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) return List.of();
        List<Document> docs = new ArrayList<>();
        for (String chunkId : chunkIds) {
            try {
                RagChunk chunk = ragChunkMapper.selectByChunkUid(chunkId);
                if (chunk != null && StringUtils.hasText(chunk.getText())) {
                    docs.add(new Document(chunk.getText(), Map.of("chunkId", chunkId)));
                }
            } catch (Exception ignored) {
            }
        }
        return docs;
    }

    public AiRagEvalRun getRunStatus(String evalRunId) {
        return runMapper.selectOne(
                new LambdaQueryWrapper<AiRagEvalRun>()
                        .eq(AiRagEvalRun::getEvalRunId, evalRunId));
    }

    public Map<String, Object> buildQualityGate(AiRagEvalRun run) {
        Map<String, MetricGateEntry> gates = new LinkedHashMap<>();
        gates.put("recallAt5", metricGate(run != null ? run.getAvgRecall() : null, evalConfig.getRecallAtKThreshold()));
        gates.put("mrr", metricGate(run != null ? run.getAvgMrr() : null, evalConfig.getMrrThreshold()));
        gates.put("ndcgAt5", metricGate(run != null ? run.getAvgNdcg() : null, evalConfig.getNdcgAtKThreshold()));
        gates.put("contextPrecision", metricGate(run != null ? run.getAvgCtxPrecision() : null, evalConfig.getContextPrecisionThreshold()));
        gates.put("contextRecall", metricGate(run != null ? run.getAvgCtxRecall() : null, evalConfig.getContextRecallThreshold()));
        gates.put("contextRelevance", metricGate(run != null ? run.getAvgContextRelevance() : null, evalConfig.getContextRelevanceThreshold()));
        gates.put("faithfulness", metricGate(run != null ? run.getAvgFaithfulness() : null, evalConfig.getFaithfulnessThreshold()));
        gates.put("answerRelevancy", metricGate(run != null ? run.getAvgAnswerRelevancy() : null, evalConfig.getAnswerRelevancyThreshold()));
        gates.put("answerCorrectness", metricGate(run != null ? run.getAvgAnswerCorrectness() : null, evalConfig.getAnswerCorrectnessThreshold()));

        long available = gates.values().stream()
                .filter(v -> v.actual() != null)
                .count();
        long passed = gates.values().stream()
                .filter(MetricGateEntry::passed)
                .count();

        String status;
        if (available == 0) {
            status = "UNKNOWN";
        } else if (passed == available) {
            status = "PASS";
        } else if (passed * 1.0 / available >= 0.6) {
            status = "WARN";
        } else {
            status = "FAIL";
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        Map<String, Object> metrics = new LinkedHashMap<>();
        gates.forEach((key, value) -> metrics.put(key, value.toMap()));
        summary.put("status", status);
        summary.put("availableMetrics", available);
        summary.put("passedMetrics", passed);
        summary.put("metrics", metrics);
        return summary;
    }

    private MetricGateEntry metricGate(Double actual, double threshold) {
        return new MetricGateEntry(actual, threshold, actual != null && actual >= threshold,
                actual == null ? null : threshold - actual);
    }

    private record MetricGateEntry(Double actual, double threshold, boolean passed, Double gap) {
        private Map<String, Object> toMap() {
            Map<String, Object> metric = new LinkedHashMap<>();
            metric.put("actual", actual);
            metric.put("threshold", threshold);
            metric.put("passed", passed);
            metric.put("gap", gap);
            return metric;
        }
    }

    List<String> parseChunkIds(String json) {
        if (json == null || json.isEmpty()) return List.of();
        return List.of(json.replace("[", "").replace("]", "").replace("\"", "").split(","));
    }

    /**
     * Parse expectedChunks JSON that may be either a flat list ["id1","id2"]
     * or a graded weight map {"id1":3,"id2":2}. Returns a map of chunkId → relevance (0-3).
     */
    Map<String, Integer> parseChunkWeights(String json) {
        Map<String, Integer> weights = new LinkedHashMap<>();
        if (json == null || json.isEmpty()) return weights;
        String trimmed = json.trim();
        if (trimmed.startsWith("[")) {
            // Flat list: all expected chunks get max relevance 3
            for (String chunkId : parseChunkIds(json)) {
                if (StringUtils.hasText(chunkId)) {
                    weights.put(chunkId.trim(), 3);
                }
            }
        } else if (trimmed.startsWith("{")) {
            // Graded map: {"chunk-1": 3, "chunk-2": 2}
            try {
                com.alibaba.fastjson2.JSONObject obj = com.alibaba.fastjson2.JSON.parseObject(trimmed);
                for (String key : obj.keySet()) {
                    weights.put(key, Math.min(3, Math.max(0, obj.getIntValue(key))));
                }
            } catch (Exception e) {
                log.warn("Failed to parse chunk weights JSON: {}", json, e);
            }
        }
        return weights;
    }

    double calculateRecallAtK(List<String> retrieved, List<String> expected, int k) {
        if (expected.isEmpty()) return 1.0;
        long hits = retrieved.stream().limit(k).filter(expected::contains).count();
        return (double) hits / expected.size();
    }

    double calculateMrr(List<String> retrieved, List<String> expected) {
        for (int i = 0; i < retrieved.size(); i++) {
            if (expected.contains(retrieved.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    double calculatePrecisionAtK(List<String> retrieved, List<String> expected, int k) {
        if (retrieved.isEmpty() || expected.isEmpty()) {
            return expected.isEmpty() ? 1.0 : 0.0;
        }
        long hits = retrieved.stream().limit(k).filter(expected::contains).count();
        return (double) hits / Math.min(retrieved.size(), k);
    }

    double calculateNdcgAtK(List<String> retrieved, List<String> expected, int k) {
        double dcg = 0;
        for (int i = 0; i < Math.min(retrieved.size(), k); i++) {
            if (expected.contains(retrieved.get(i))) {
                dcg += 1.0 / (Math.log(i + 2) / Math.log(2));
            }
        }
        double idealDcg = 0;
        for (int i = 0; i < Math.min(expected.size(), k); i++) {
            idealDcg += 1.0 / (Math.log(i + 2) / Math.log(2));
        }
        return idealDcg > 0 ? dcg / idealDcg : 0;
    }

    /**
     * Graded NDCG: uses actual relevance grades per chunk (0-3 scale).
     * DCG = Σ (2^rel_i - 1) / log₂(i + 2) for top-k ranked chunks.
     * IDCG is computed by sorting retrieved relevance grades descending and assuming
     * it's possible to place all high-relevance chunks in top positions.
     */
    double calculateNdcgGraded(List<String> retrieved, Map<String, Integer> chunkWeights, int k) {
        if (retrieved.isEmpty() || chunkWeights.isEmpty()) return 0.0;
        double dcg = 0;
        for (int i = 0; i < Math.min(retrieved.size(), k); i++) {
            int rel = chunkWeights.getOrDefault(retrieved.get(i), 0);
            dcg += (Math.pow(2, rel) - 1) / (Math.log(i + 2) / Math.log(2));
        }
        // Ideal DCG: assume all chunks with rel>0 are placed at top positions, sorted desc
        List<Integer> idealRels = chunkWeights.values().stream()
                .filter(r -> r > 0)
                .sorted(Comparator.reverseOrder())
                .toList();
        double idealDcg = 0;
        for (int i = 0; i < Math.min(idealRels.size(), k); i++) {
            idealDcg += (Math.pow(2, idealRels.get(i)) - 1) / (Math.log(i + 2) / Math.log(2));
        }
        return idealDcg > 0 ? dcg / idealDcg : 0.0;
    }
}
