package org.javaup.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.alibaba.fastjson2.JSON;
import org.javaup.ai.assistant.skill.ops.nl2sql.Nl2SqlException;
import org.javaup.ai.assistant.skill.ops.nl2sql.Nl2SqlExecutionResult;
import org.javaup.ai.assistant.skill.ops.nl2sql.Nl2SqlOrchestrator;
import org.javaup.ai.assistant.skill.ops.nl2sql.Nl2SqlValidatedSql;
import org.javaup.ai.entity.AiNl2SqlEvalCase;
import org.javaup.ai.entity.AiNl2SqlEvalResult;
import org.javaup.ai.entity.AiNl2SqlEvalRun;
import org.javaup.ai.mapper.AiNl2SqlEvalCaseMapper;
import org.javaup.ai.mapper.AiNl2SqlEvalResultMapper;
import org.javaup.ai.mapper.AiNl2SqlEvalRunMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Service
public class Nl2SqlEvalService {

    private static final ExecutorService EVAL_POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "nl2sql-eval-worker");
        t.setDaemon(true);
        return t;
    });
    private static final int MAX_CONCURRENCY = 3;
    private static final int RUN_TIMEOUT_MINUTES = 30;

    private final AiNl2SqlEvalCaseMapper caseMapper;
    private final AiNl2SqlEvalRunMapper runMapper;
    private final AiNl2SqlEvalResultMapper resultMapper;
    private final Nl2SqlOrchestrator orchestrator;
    private ApplicationContext applicationContext;

    public Nl2SqlEvalService(AiNl2SqlEvalCaseMapper caseMapper,
                              AiNl2SqlEvalRunMapper runMapper,
                              AiNl2SqlEvalResultMapper resultMapper,
                              Nl2SqlOrchestrator orchestrator) {
        this.caseMapper = caseMapper;
        this.runMapper = runMapper;
        this.resultMapper = resultMapper;
        this.orchestrator = orchestrator;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    public AiNl2SqlEvalRun startEvaluation(String category, String difficulty, List<String> caseIds) {
        LambdaQueryWrapper<AiNl2SqlEvalCase> wrapper = new LambdaQueryWrapper<AiNl2SqlEvalCase>()
                .eq(AiNl2SqlEvalCase::getStatus, 1);
        if (StringUtils.hasText(category)) {
            wrapper.eq(AiNl2SqlEvalCase::getCategory, category);
        }
        if (StringUtils.hasText(difficulty)) {
            wrapper.eq(AiNl2SqlEvalCase::getDifficulty, difficulty);
        }
        if (caseIds != null && !caseIds.isEmpty()) {
            wrapper.in(AiNl2SqlEvalCase::getCaseId, caseIds);
        }
        List<AiNl2SqlEvalCase> cases = caseMapper.selectList(wrapper);
        if (cases.isEmpty()) {
            throw new RuntimeException("No NL2SQL eval cases found");
        }

        AiNl2SqlEvalRun run = new AiNl2SqlEvalRun();
        run.setEvalRunId(UUID.randomUUID().toString().replace("-", ""));
        run.setTotalCases(cases.size());
        run.setCompletedCases(0);
        run.setRunStatus("RUNNING");
        run.setCreateTime(new Date());
        run.setEditTime(new Date());
        run.setStatus(1);
        runMapper.insert(run);

        Nl2SqlEvalService self = applicationContext != null
                ? applicationContext.getBean(Nl2SqlEvalService.class) : this;
        self.executeAsync(run, cases);
        return run;
    }

    @Async
    public void executeAsync(AiNl2SqlEvalRun run, List<AiNl2SqlEvalCase> cases) {
        Semaphore semaphore = new Semaphore(MAX_CONCURRENCY);
        AtomicInteger completed = new AtomicInteger(0);
        AtomicInteger validCount = new AtomicInteger(0);
        AtomicInteger execSuccessCount = new AtomicInteger(0);
        AtomicInteger exactMatchCount = new AtomicInteger(0);
        AtomicInteger resultSetEquivalentCount = new AtomicInteger(0);
        AtomicInteger unsafeRejectedCount = new AtomicInteger(0);
        AtomicInteger lowConfidenceClarifiedCount = new AtomicInteger(0);
        AtomicInteger repairAttemptedCount = new AtomicInteger(0);
        AtomicInteger repairSucceededCount = new AtomicInteger(0);
        List<Long> latencies = new ArrayList<>();
        List<Double> schemaLinkPrecisions = new ArrayList<>();
        List<Double> schemaLinkRecalls = new ArrayList<>();
        List<Double> costs = new ArrayList<>();
        List<AiNl2SqlEvalResult> results = new ArrayList<>();

        List<Future<?>> futures = new ArrayList<>();
        for (AiNl2SqlEvalCase evalCase : cases) {
            futures.add(EVAL_POOL.submit(() -> {
                boolean acquired = false;
                try {
                    semaphore.tryAcquire(RUN_TIMEOUT_MINUTES, TimeUnit.MINUTES);
                    acquired = true;

                    AiNl2SqlEvalResult result = evaluateCase(evalCase, run.getEvalRunId());
                    synchronized (results) {
                        results.add(result);
                        if (result.getIsValidSql() != null && result.getIsValidSql() == 1) {
                            validCount.incrementAndGet();
                        }
                        if (result.getExecuteSuccess() != null && result.getExecuteSuccess() == 1) {
                            execSuccessCount.incrementAndGet();
                        }
                        if (result.getExactMatch() != null && result.getExactMatch() == 1) {
                            exactMatchCount.incrementAndGet();
                        }
                        if (result.getResultSetEquivalent() != null && result.getResultSetEquivalent() == 1) {
                            resultSetEquivalentCount.incrementAndGet();
                        }
                        if (result.getUnsafeRejected() != null && result.getUnsafeRejected() == 1) {
                            unsafeRejectedCount.incrementAndGet();
                        }
                        if (result.getLowConfidenceClarified() != null && result.getLowConfidenceClarified() == 1) {
                            lowConfidenceClarifiedCount.incrementAndGet();
                        }
                        if (result.getRepairAttempted() != null && result.getRepairAttempted() == 1) {
                            repairAttemptedCount.incrementAndGet();
                        }
                        if (result.getRepairSucceeded() != null && result.getRepairSucceeded() == 1) {
                            repairSucceededCount.incrementAndGet();
                        }
                        if (result.getSchemaLinkPrecision() != null) {
                            schemaLinkPrecisions.add(result.getSchemaLinkPrecision());
                        }
                        if (result.getSchemaLinkRecall() != null) {
                            schemaLinkRecalls.add(result.getSchemaLinkRecall());
                        }
                        if (result.getEstimatedCost() != null) {
                            costs.add(result.getEstimatedCost());
                        }
                        if (result.getLatencyMs() != null) {
                            latencies.add(result.getLatencyMs());
                        }
                        completed.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    if (acquired) {
                        semaphore.release();
                    }
                }
            }));
        }

        try {
            for (Future<?> future : futures) {
                future.get(RUN_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            }
        } catch (Exception e) {
            log.error("NL2SQL eval execution error", e);
        }

        int total = cases.size();
        int done = completed.get();
        run.setCompletedCases(done);
        run.setSqlValidityRate(done > 0 ? (double) validCount.get() / done : 0.0);
        run.setExecutionAccuracy(done > 0 ? (double) execSuccessCount.get() / done : 0.0);
        run.setExactMatchRate(done > 0 ? (double) exactMatchCount.get() / done : 0.0);
        run.setResultSetEquivalenceRate(done > 0 ? (double) resultSetEquivalentCount.get() / done : 0.0);
        run.setUnsafeRejectionRate(done > 0 ? (double) unsafeRejectedCount.get() / done : 0.0);
        run.setLowConfidenceClarificationRate(done > 0 ? (double) lowConfidenceClarifiedCount.get() / done : 0.0);
        run.setRepairSuccessRate(repairAttemptedCount.get() > 0 ? (double) repairSucceededCount.get() / repairAttemptedCount.get() : 0.0);
        run.setSchemaLinkPrecision(schemaLinkPrecisions.isEmpty() ? 0.0
                : schemaLinkPrecisions.stream().mapToDouble(Double::doubleValue).average().orElse(0.0));
        run.setSchemaLinkRecall(schemaLinkRecalls.isEmpty() ? 0.0
                : schemaLinkRecalls.stream().mapToDouble(Double::doubleValue).average().orElse(0.0));
        run.setAvgLatencyMs(latencies.isEmpty() ? 0.0
                : latencies.stream().mapToLong(Long::longValue).average().orElse(0.0));
        run.setAvgEstimatedCost(costs.isEmpty() ? 0.0
                : costs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0));
        run.setRunStatus(done == total ? "COMPLETED" : "COMPLETED_WITH_ERRORS");
        run.setEditTime(new Date());
        runMapper.updateById(run);
    }

    private AiNl2SqlEvalResult evaluateCase(AiNl2SqlEvalCase evalCase, String evalRunId) {
        AiNl2SqlEvalResult result = new AiNl2SqlEvalResult();
        result.setEvalRunId(evalRunId);
        result.setCaseId(evalCase.getCaseId());
        result.setQuestion(evalCase.getQuestion());
        result.setEvalMethod("NL2SQL_PIPELINE_FULL");
        result.setCreateTime(new Date());
        result.setEditTime(new Date());
        result.setStatus(1);

        String runId = "eval-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        long start = System.currentTimeMillis();

        try {
            Map<String, Object> evidence = orchestrator.answer(runId, evalCase.getQuestion(), null);
            long elapsed = System.currentTimeMillis() - start;
            result.setLatencyMs(elapsed);

            Object status = evidence.get("status");
            result.setSchemaLinkingEvidenceJson(JSON.toJSONString(evidence.getOrDefault("schemaLinkingEvidence", Map.of())));
            result.setSafetyReportJson(JSON.toJSONString(evidence.getOrDefault("safetyReport", Map.of())));
            result.setRepairTraceJson(JSON.toJSONString(repairTrace(evidence)));
            result.setEstimatedCost(costFromEvidence(evidence));
            applySchemaLinkMetrics(evalCase, evidence, result);
            applyGovernanceFlags(evidence, result);
            if ("FAILED".equals(status)) {
                Object msg = evidence.get("message");
                if (msg != null && msg.toString().contains("安全校验")) {
                    result.setIsValidSql(0);
                    result.setUnsafeRejected(1);
                }
                result.setErrorMessage(msg != null ? msg.toString() : "Unknown error");
            } else if ("NEED_CLARIFICATION".equals(status)) {
                result.setIsValidSql(1);
                result.setExecuteSuccess(0);
                result.setGeneratedSql("(clarification needed)");
                result.setErrorMessage("LLM determined insufficient info to generate SQL");
            } else if ("COMPLETED".equals(status) || "SQL_READY".equals(status)) {
                result.setIsValidSql(1);

                Object validatedSql = evidence.get("validatedSql");
                if (validatedSql instanceof Nl2SqlValidatedSql sql) {
                    result.setGeneratedSql(sql.sql());
                } else if (validatedSql != null) {
                    result.setGeneratedSql(String.valueOf(validatedSql));
                }

                Object execution = evidence.get("execution");
                if (execution instanceof Nl2SqlExecutionResult) {
                    Nl2SqlExecutionResult execResult = (Nl2SqlExecutionResult) execution;
                    if (execResult.skipped()) {
                        result.setExecuteSuccess(0);
                        result.setErrorMessage("Execution skipped: " + execResult.skipReason());
                    } else {
                        result.setExecuteSuccess(1);
                        if (StringUtils.hasText(evalCase.getExpectedResultJson())) {
                            result.setResultSetEquivalent(resultSetEquivalent(evalCase.getExpectedResultJson(), execResult.rows()) ? 1 : 0);
                        }
                    }
                }

                if (StringUtils.hasText(evalCase.getExpectedSql())) {
                    result.setExactMatch(normalizeSql(evalCase.getExpectedSql())
                            .equals(normalizeSql(result.getGeneratedSql())) ? 1 : 0);
                }
            } else if ("DISABLED".equals(status)) {
                result.setErrorMessage("NL2SQL disabled");
            }
        } catch (Nl2SqlException e) {
            long elapsed = System.currentTimeMillis() - start;
            result.setLatencyMs(elapsed);
            result.setIsValidSql(0);
            result.setUnsafeRejected(1);
            result.setErrorMessage("Validation failed: " + e.getMessage());
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            result.setLatencyMs(elapsed);
            result.setErrorMessage(e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        resultMapper.insert(result);
        return result;
    }

    private String normalizeSql(String sql) {
        if (sql == null) return "";
        return sql.trim().toLowerCase()
                .replaceAll("\\s+", " ")
                .replaceAll("\\s*,\\s*", ",")
                .replaceAll("\\s*\\(\\s*", "(")
                .replaceAll("\\s*\\)\\s*", ")")
                .replaceAll(";\\s*$", "");
    }

    private void applyGovernanceFlags(Map<String, Object> evidence, AiNl2SqlEvalResult result) {
        Map<String, Object> safetyReport = mapValue(evidence.get("safetyReport"));
        if (Boolean.TRUE.equals(safetyReport.get("lowConfidenceBlocked"))) {
            result.setLowConfidenceClarified(1);
        }
        if (Boolean.TRUE.equals(safetyReport.get("unsafeExecutionRejected"))) {
            result.setUnsafeRejected(1);
        }
        if (evidence.containsKey("repairGeneration") || evidence.containsKey("repairedSql")) {
            result.setRepairAttempted(1);
        }
        if (evidence.containsKey("repairedSql")) {
            result.setRepairSucceeded(1);
        }
    }

    private void applySchemaLinkMetrics(AiNl2SqlEvalCase evalCase, Map<String, Object> evidence, AiNl2SqlEvalResult result) {
        List<String> expectedTables = splitCsv(evalCase.getExpectedTableNames());
        Map<String, Object> schemaEvidence = mapValue(evidence.get("schemaLinkingEvidence"));
        List<String> predictedTables = listValue(schemaEvidence.get("tables"));
        if (expectedTables.isEmpty() && predictedTables.isEmpty()) {
            return;
        }
        long overlap = predictedTables.stream().filter(expectedTables::contains).count();
        result.setSchemaLinkPrecision(predictedTables.isEmpty() ? 0.0 : (double) overlap / predictedTables.size());
        result.setSchemaLinkRecall(expectedTables.isEmpty() ? 0.0 : (double) overlap / expectedTables.size());
    }

    private Map<String, Object> repairTrace(Map<String, Object> evidence) {
        Map<String, Object> trace = new LinkedHashMap<>();
        if (evidence.containsKey("repairGeneration")) {
            trace.put("repairGeneration", evidence.get("repairGeneration"));
        }
        if (evidence.containsKey("repairedSql")) {
            trace.put("repairedSql", evidence.get("repairedSql"));
        }
        return trace;
    }

    private Double costFromEvidence(Map<String, Object> evidence) {
        Object cost = evidence.get("estimatedCost");
        if (cost instanceof Number number) {
            return number.doubleValue();
        }
        if (cost != null) {
            try {
                return Double.parseDouble(String.valueOf(cost));
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .collect(Collectors.toMap(entry -> String.valueOf(entry.getKey()), Map.Entry::getValue,
                            (left, right) -> right, LinkedHashMap::new));
        }
        return Map.of();
    }

    private List<String> listValue(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(String::valueOf)
                    .filter(StringUtils::hasText)
                    .map(item -> item.trim().toLowerCase())
                    .toList();
        }
        return List.of();
    }

    private List<String> splitCsv(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split(","))
                .map(item -> item.trim().toLowerCase())
                .filter(StringUtils::hasText)
                .toList();
    }

    private boolean resultSetEquivalent(String expectedResultJson, List<Map<String, Object>> actualRows) {
        try {
            Object expected = JSON.parse(expectedResultJson);
            Object actual = JSON.parse(JSON.toJSONString(actualRows == null ? List.of() : actualRows));
            return JSON.toJSONString(expected).equals(JSON.toJSONString(actual));
        } catch (Exception ignored) {
            return false;
        }
    }

    public AiNl2SqlEvalRun getRunStatus(String evalRunId) {
        return runMapper.selectOne(new LambdaQueryWrapper<AiNl2SqlEvalRun>()
                .eq(AiNl2SqlEvalRun::getEvalRunId, evalRunId));
    }

    public List<AiNl2SqlEvalResult> listRunResults(String evalRunId) {
        return resultMapper.selectList(new LambdaQueryWrapper<AiNl2SqlEvalResult>()
                .eq(AiNl2SqlEvalResult::getEvalRunId, evalRunId)
                .orderByDesc(AiNl2SqlEvalResult::getId));
    }
}
