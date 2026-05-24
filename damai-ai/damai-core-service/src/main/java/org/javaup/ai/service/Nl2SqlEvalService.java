package org.javaup.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.assistant.skill.ops.nl2sql.Nl2SqlException;
import org.javaup.ai.assistant.skill.ops.nl2sql.Nl2SqlExecutionResult;
import org.javaup.ai.assistant.skill.ops.nl2sql.Nl2SqlOrchestrator;
import org.javaup.ai.entity.AiNl2SqlEvalCase;
import org.javaup.ai.entity.AiNl2SqlEvalResult;
import org.javaup.ai.entity.AiNl2SqlEvalRun;
import org.javaup.ai.mapper.AiNl2SqlEvalCaseMapper;
import org.javaup.ai.mapper.AiNl2SqlEvalResultMapper;
import org.javaup.ai.mapper.AiNl2SqlEvalRunMapper;
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
        List<Long> latencies = new ArrayList<>();
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
        run.setAvgLatencyMs(latencies.isEmpty() ? 0.0
                : latencies.stream().mapToLong(Long::longValue).average().orElse(0.0));
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
            if ("FAILED".equals(status)) {
                Object msg = evidence.get("message");
                if (msg != null && msg.toString().contains("安全校验")) {
                    result.setIsValidSql(0);
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
                if (validatedSql != null) {
                    result.setGeneratedSql(validatedSql.toString());
                }

                Object execution = evidence.get("execution");
                if (execution instanceof Nl2SqlExecutionResult) {
                    Nl2SqlExecutionResult execResult = (Nl2SqlExecutionResult) execution;
                    if (execResult.skipped()) {
                        result.setExecuteSuccess(0);
                        result.setErrorMessage("Execution skipped: " + execResult.skipReason());
                    } else {
                        result.setExecuteSuccess(1);
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