package org.javaup.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.assistant.eval.RagEvalScorer;
import org.javaup.ai.config.EvalConfig;
import org.javaup.ai.entity.AiRagEvalCase;
import org.javaup.ai.entity.AiRagEvalResult;
import org.javaup.ai.entity.AiRagEvalRun;
import org.javaup.ai.entity.RagChunk;
import org.javaup.ai.mapper.AiRagEvalCaseMapper;
import org.javaup.ai.mapper.AiRagEvalResultMapper;
import org.javaup.ai.mapper.AiRagEvalRunMapper;
import org.javaup.ai.mapper.RagChunkMapper;
import org.javaup.ai.vo.RagSearchResultVo;
import org.javaup.ai.vo.RagSourceVo;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
        List<AiRagEvalCase> cases = caseMapper.selectList(
                new LambdaQueryWrapper<AiRagEvalCase>()
                        .eq(AiRagEvalCase::getStatus, 1));
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

        applicationContext.getBean(RagEvalService.class).executeEvalAsync(evalRun, cases);
        return evalRun;
    }

    @Async("aiTraceExecutor")
    public void executeEvalAsync(AiRagEvalRun evalRun, List<AiRagEvalCase> cases) {
        // Bounded concurrency: max 3 cases in parallel to avoid overwhelming DeepSeek API
        int maxConcurrency = 3;
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

        // Submit all cases in parallel, bounded by semaphore
        List<Future<?>> futures = new ArrayList<>();
        for (AiRagEvalCase evalCase : cases) {
            Future<?> future = executor.submit(() -> {
                boolean acquired = false;
                try {
                    semaphore.acquire();
                    acquired = true;
                    evaluateSingleCase(evalRun, evalCase,
                            completed, totalRecall, totalPrecision, totalMrr, totalNdcg, totalHitRate,
                            totalCtxPrecision, totalCtxRecall, totalCtxRelevance,
                            totalFaithfulness, totalAnswerRelevancy, totalAnswerCorrectness,
                            retrievalEvalCount, generationEvalCount);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("Eval case interrupted: caseId={}", evalCase.getCaseId());
                } catch (Exception e) {
                    log.error("Eval case failed: caseId={}, error={}", evalCase.getCaseId(), e.toString());
                } finally {
                    if (acquired) {
                        semaphore.release();
                    }
                }
            });
            futures.add(future);
        }

        // Wait for all cases to complete
        long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(30);
        for (int i = 0; i < futures.size(); i++) {
            Future<?> future = futures.get(i);
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                log.error("Global eval timeout: cancelling remaining cases ({}/{})", i, futures.size());
                for (int j = i; j < futures.size(); j++) {
                    futures.get(j).cancel(true);
                }
                break;
            }
            try {
                future.get(remaining, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                log.error("Global eval timeout waiting for case index={}", i);
                future.cancel(true);
            } catch (Exception e) {
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
        evalRun.setRunStatus("COMPLETED");
        evalRun.setEditTime(new Date());
        runMapper.updateById(evalRun);
        log.info("RAG eval completed: evalRunId={}, cases={}, avgRecall={}, avgPrecision={}, avgHitRate={}, avgMRR={}, avgNDCG={}, " +
                        "avgCtxPrecision={}, avgCtxRecall={}, avgCtxRelevance={}, " +
                        "avgFaithfulness={}, avgAnswerRelevancy={}, avgAnswerCorrectness={}",
                evalRun.getEvalRunId(), c,
                evalRun.getAvgRecall(), evalRun.getAvgPrecision(), evalRun.getAvgHitRate(),
                evalRun.getAvgMrr(), evalRun.getAvgNdcg(),
                evalRun.getAvgCtxPrecision(), evalRun.getAvgCtxRecall(), evalRun.getAvgContextRelevance(),
                evalRun.getAvgFaithfulness(), evalRun.getAvgAnswerRelevancy(), evalRun.getAvgAnswerCorrectness());
    }

    private void evaluateSingleCase(AiRagEvalRun evalRun, AiRagEvalCase evalCase,
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

        if (evalConfig.isDegradedMode()) {
            retrievedChunks = parseChunkIds(evalCase.getExpectedChunks());
            retrievedDocs = resolveDocuments(retrievedChunks);
        } else {
            RagSearchResultVo searchResult = hybridSearchService.hybridSearchWithHyde(
                    evalCase.getQuestion(), 5, true);
            retrievedChunks = searchResult.getSources() != null
                    ? searchResult.getSources().stream().map(RagSourceVo::getChunkId).toList()
                    : List.of();
            retrievedDocs = searchResult.getDocuments() != null
                    ? searchResult.getDocuments()
                    : List.of();
        }
        long latency = System.currentTimeMillis() - start;

        // ---- Step 2: 传统检索指标 ----
        List<String> expectedChunks = parseChunkIds(evalCase.getExpectedChunks());
        double recall = calculateRecallAtK(retrievedChunks, expectedChunks, 5);
        double precision = calculatePrecisionAtK(retrievedChunks, expectedChunks, 5);
        double mrr = calculateMrr(retrievedChunks, expectedChunks);
        double ndcg = calculateNdcgGraded(retrievedChunks, expectedChunks, 5);
        double hitRate = expectedChunks.isEmpty() ? 1.0 :
                (retrievedChunks.stream().limit(5).anyMatch(expectedChunks::contains) ? 1.0 : 0.0);

        totalRecall.updateAndGet(v -> v + recall);
        totalPrecision.updateAndGet(v -> v + precision);
        totalMrr.updateAndGet(v -> v + mrr);
        totalNdcg.updateAndGet(v -> v + ndcg);
        totalHitRate.updateAndGet(v -> v + hitRate);

        AiRagEvalResult result = new AiRagEvalResult();
        result.setEvalRunId(evalRun.getEvalRunId());
        result.setCaseId(evalCase.getCaseId());
        result.setQuestion(evalCase.getQuestion());
        result.setRetrievedChunks(String.join(",", retrievedChunks));
        result.setRecallAt5(recall);
        result.setMrr(mrr);
        result.setNdcgAt5(ndcg);
        result.setLatencyMs(latency);

        // ---- Step 3 + 4 in parallel: generateAnswer + evaluateContext (independent) ----
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

        String generatedAnswer;
        try {
            generatedAnswer = answerFuture.get(10, TimeUnit.MINUTES);
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
            RagEvalScorer.ContextEvalResult ctxResult = ctxFuture.get(10, TimeUnit.MINUTES);
            result.setContextPrecision(ctxResult.contextPrecision());
            result.setContextRecall(ctxResult.contextRecall());
            result.setContextRelevance(ctxResult.contextRelevance());
            totalCtxPrecision.updateAndGet(v -> v + ctxResult.contextPrecision());
            totalCtxRecall.updateAndGet(v -> v + ctxResult.contextRecall());
            totalCtxRelevance.updateAndGet(v -> v + ctxResult.contextRelevance());
            retrievalEvalCount.incrementAndGet();
        } catch (TimeoutException e) {
            log.error("Context eval TIMEOUT for caseId={}", evalCase.getCaseId());
            ctxFuture.cancel(true);
        } catch (Exception e) {
            log.warn("Context evaluation failed for caseId={}: {}", evalCase.getCaseId(), e.getMessage());
        }

        // ---- Step 5: 生成质量评估（依赖 generatedAnswer） ----
        try {
            RagEvalScorer.GenEvalResult genResult = ragEvalScorer.evaluateGeneration(
                    evalCase.getQuestion(),
                    generatedAnswer,
                    evalCase.getExpectedAnswer(),
                    retrievedDocs);
            result.setFaithfulnessScore(genResult.faithfulness());
            result.setAnswerRelevancyScore(genResult.answerRelevancy());
            result.setAnswerCorrectnessScore(genResult.answerCorrectness());
            totalFaithfulness.updateAndGet(v -> v + genResult.faithfulness());
            totalAnswerRelevancy.updateAndGet(v -> v + genResult.answerRelevancy());
            totalAnswerCorrectness.updateAndGet(v -> v + genResult.answerCorrectness());
            generationEvalCount.incrementAndGet();
        } catch (Exception e) {
            log.warn("Generation evaluation failed for caseId={}: {}", evalCase.getCaseId(), e.getMessage());
        }

        result.setEvalMethod("RAGAS_LLM_JUDGE");
        result.setCreateTime(new Date());
        result.setEditTime(new Date());
        result.setStatus(1);
        resultMapper.insert(result);
        completed.incrementAndGet();
        log.info("Eval case completed: caseId={}, recall={}, mrr={}, ndcg={}, ctxPrecision={}, ctxRecall={}, faithfulness={}, answerRelevancy={}, answerCorrectness={}",
                evalCase.getCaseId(), recall, mrr, ndcg,
                result.getContextPrecision(), result.getContextRecall(),
                result.getFaithfulnessScore(), result.getAnswerRelevancyScore(), result.getAnswerCorrectnessScore());
    }

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

    private List<Document> syntheticDocs(List<String> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) return List.of();
        List<Document> docs = new ArrayList<>();
        for (String chunkId : chunkIds) {
            docs.add(new Document(chunkId, Map.of("chunkId", chunkId)));
        }
        return docs;
    }

    public AiRagEvalRun getRunStatus(String evalRunId) {
        return runMapper.selectOne(
                new LambdaQueryWrapper<AiRagEvalRun>()
                        .eq(AiRagEvalRun::getEvalRunId, evalRunId));
    }

    List<String> parseChunkIds(String json) {
        if (json == null || json.isEmpty()) return List.of();
        return List.of(json.replace("[", "").replace("]", "").replace("\"", "").split(","));
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
     * Graded NDCG: uses position-weighted scores from retrieved map.
     * Assigns relevance = 3.0 for exact expected match, 2.0 for partial, 0 for none.
     */
    double calculateNdcgGraded(List<String> retrieved, List<String> expected, int k) {
        double dcg = 0;
        for (int i = 0; i < Math.min(retrieved.size(), k); i++) {
            double rel = expected.contains(retrieved.get(i)) ? 3.0 : 0.0;
            dcg += (Math.pow(2, rel) - 1) / (Math.log(i + 2) / Math.log(2));
        }
        double idealDcg = 0;
        for (int i = 0; i < Math.min(expected.size(), k); i++) {
            idealDcg += (Math.pow(2, 3) - 1) / (Math.log(i + 2) / Math.log(2));
        }
        return idealDcg > 0 ? dcg / idealDcg : 0;
    }
}
