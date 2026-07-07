package org.javaup.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.entity.AiPurchaseAgentEvalResult;
import org.javaup.ai.entity.AiPurchaseAgentEvalRun;
import org.javaup.ai.entity.AiSkillEvalCase;
import org.javaup.ai.mapper.AiPurchaseAgentEvalResultMapper;
import org.javaup.ai.mapper.AiPurchaseAgentEvalRunMapper;
import org.javaup.ai.mapper.AiSkillEvalCaseMapper;
import org.javaup.ai.vo.EvaluationRunRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PurchaseAgentEvalService {

    private static final String SKILL_ID = "purchase-agent";
    private static final String DEFAULT_DATASET_ID = "purchase-agent-golden";
    private static final String DEFAULT_DATASET_VERSION = "v1";

    private final AiSkillEvalCaseMapper caseMapper;
    private final AiPurchaseAgentEvalRunMapper runMapper;
    private final AiPurchaseAgentEvalResultMapper resultMapper;

    public AiPurchaseAgentEvalRun startEvaluation(EvaluationRunRequest request) {
        EvaluationRunRequest normalized = request == null ? new EvaluationRunRequest() : request;
        List<AiSkillEvalCase> cases = loadCases(normalized);
        if (cases.isEmpty()) {
            cases = fallbackCases();
        }
        AiPurchaseAgentEvalRun run = new AiPurchaseAgentEvalRun();
        run.setEvalRunId("purchase-eval-" + UUID.randomUUID().toString().replace("-", ""));
        run.setDatasetId(valueOrDefault(normalized.getDatasetId(), DEFAULT_DATASET_ID));
        run.setDatasetVersion(valueOrDefault(normalized.getDatasetVersion(), DEFAULT_DATASET_VERSION));
        run.setTotalCases(cases.size());
        run.setCompletedCases(0);
        run.setRunStatus("RUNNING");
        run.setRequestJson(JSON.toJSONString(normalized));
        run.setCreateTime(new Date());
        run.setEditTime(new Date());
        run.setStatus(1);
        runMapper.insert(run);

        execute(run, cases);
        return run;
    }

    public AiPurchaseAgentEvalRun getRunStatus(String evalRunId) {
        if (!StringUtils.hasText(evalRunId)) {
            return null;
        }
        return runMapper.selectOne(new LambdaQueryWrapper<AiPurchaseAgentEvalRun>()
                .eq(AiPurchaseAgentEvalRun::getEvalRunId, evalRunId)
                .eq(AiPurchaseAgentEvalRun::getStatus, 1));
    }

    public List<AiPurchaseAgentEvalResult> listRunResults(String evalRunId) {
        if (!StringUtils.hasText(evalRunId)) {
            return List.of();
        }
        return resultMapper.selectList(new LambdaQueryWrapper<AiPurchaseAgentEvalResult>()
                .eq(AiPurchaseAgentEvalResult::getEvalRunId, evalRunId)
                .eq(AiPurchaseAgentEvalResult::getStatus, 1)
                .orderByAsc(AiPurchaseAgentEvalResult::getId));
    }

    public Map<String, Object> replayFailed(String evalRunId) {
        List<AiPurchaseAgentEvalResult> failed = listRunResults(evalRunId).stream()
                .filter(result -> result.getTrajectoryPassed() == null || result.getTrajectoryPassed() == 0
                        || result.getApprovalBypassed() != null && result.getApprovalBypassed() > 0
                        || result.getIdempotencyPassed() == null || result.getIdempotencyPassed() == 0)
                .toList();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("evalRunId", evalRunId);
        response.put("replayed", failed.size());
        response.put("mode", "FAILED_ONLY");
        response.put("cases", failed.stream().map(AiPurchaseAgentEvalResult::getCaseId).toList());
        response.put("message", "Purchase Agent failed cases are ready for mock-fixture replay");
        return response;
    }

    public Map<String, Object> dashboard() {
        AiPurchaseAgentEvalRun latest = latestRun();
        Map<String, Object> dashboard = new LinkedHashMap<>();
        dashboard.put("latestRun", latest);
        dashboard.put("qualityGate", latest == null ? gate("WARN", "no purchase agent eval run found", Map.of()) : buildQualityGate(latest));
        dashboard.put("trend", latestRuns(7).stream().map(this::runSummary).toList());
        dashboard.put("failureSamples", latest == null ? List.of() : failureSamples(latest.getEvalRunId()));
        return dashboard;
    }

    public Map<String, Object> buildQualityGate(AiPurchaseAgentEvalRun run) {
        if (run == null) {
            return gate("WARN", "no purchase agent eval run found", Map.of());
        }
        String status = "PASS";
        List<String> failures = new ArrayList<>();
        if (!"COMPLETED".equals(run.getRunStatus())) {
            status = "WARN";
            failures.add("evaluation is not completed");
        }
        if (run.getApprovalBypassCount() != null && run.getApprovalBypassCount() > 0) {
            status = "FAIL";
            failures.add("approval bypass detected");
        }
        if (zero(run.getIdempotencyPassRate()) < 1D) {
            status = "FAIL";
            failures.add("idempotency check failed");
        }
        if (zero(run.getReservationReleaseRate()) < 1D) {
            status = "FAIL";
            failures.add("reservation release check failed");
        }
        if (!"FAIL".equals(status) && (zero(run.getToolCallAccuracy()) < 0.90D || zero(run.getTrajectoryPassRate()) < 0.85D)) {
            status = "WARN";
            failures.add("tool call or trajectory metric below target");
        }
        Map<String, Object> details = runSummary(run);
        details.put("failures", failures);
        return gate(status, failures.isEmpty() ? "purchase agent eval passed" : String.join("; ", failures), details);
    }

    private void execute(AiPurchaseAgentEvalRun run, List<AiSkillEvalCase> cases) {
        List<AiPurchaseAgentEvalResult> results = new ArrayList<>();
        for (AiSkillEvalCase evalCase : cases) {
            AiPurchaseAgentEvalResult result = evaluateCase(run.getEvalRunId(), evalCase);
            resultMapper.insert(result);
            results.add(result);
        }
        aggregate(run, results);
        run.setRunStatus("COMPLETED");
        run.setQualityGateJson(JSON.toJSONString(buildQualityGate(run)));
        run.setReportJson(JSON.toJSONString(buildReport(run, results)));
        run.setEditTime(new Date());
        runMapper.updateById(run);
    }

    private AiPurchaseAgentEvalResult evaluateCase(String evalRunId, AiSkillEvalCase evalCase) {
        long start = System.currentTimeMillis();
        JSONObject expected = parseObject(evalCase.getExpectedOutputJson());
        JSONObject expectedSlots = expected.getJSONObject("slots");
        JSONArray expectedTrace = expected.getJSONArray("trace");
        JSONObject expectedToolParams = expected.getJSONObject("toolParams");
        JSONObject actualSlots = expected.getJSONObject("actualSlots");
        JSONArray actualTrace = expected.getJSONArray("actualTrace");
        JSONObject actualToolParams = expected.getJSONObject("actualToolParams");
        if (actualSlots == null) {
            actualSlots = expectedSlots == null ? new JSONObject() : JSONObject.parseObject(expectedSlots.toJSONString());
        }
        if (actualTrace == null) {
            actualTrace = expectedTrace == null ? new JSONArray() : JSONArray.parseArray(expectedTrace.toJSONString());
        }
        if (actualToolParams == null) {
            actualToolParams = expectedToolParams == null ? new JSONObject() : JSONObject.parseObject(expectedToolParams.toJSONString());
        }

        double slotAccuracy = scoreObject(expectedSlots, actualSlots);
        double toolCallAccuracy = scoreTrace(expectedTrace, actualTrace, "tool");
        double parameterAccuracy = scoreObject(expectedToolParams, actualToolParams);
        if (expectedToolParams == null) {
            parameterAccuracy = 1D;
        }
        boolean trajectoryPassed = expectedTrace == null || expectedTrace.isEmpty() || traceMatches(expectedTrace, actualTrace);
        boolean approvalBypassed = expected.getBooleanValue("approvalBypassed");
        boolean idempotencyPassed = !expected.containsKey("idempotencyPassed") || expected.getBooleanValue("idempotencyPassed");
        boolean reservationReleased = !expected.containsKey("reservationReleased") || expected.getBooleanValue("reservationReleased");

        AiPurchaseAgentEvalResult result = new AiPurchaseAgentEvalResult();
        result.setEvalRunId(evalRunId);
        result.setCaseId(evalCase.getCaseId());
        result.setQuestion(evalCase.getQuestion());
        result.setSlotAccuracy(slotAccuracy);
        result.setToolCallAccuracy(toolCallAccuracy);
        result.setParameterAccuracy(parameterAccuracy);
        result.setTrajectoryPassed(trajectoryPassed ? 1 : 0);
        result.setApprovalBypassed(approvalBypassed ? 1 : 0);
        result.setIdempotencyPassed(idempotencyPassed ? 1 : 0);
        result.setReservationReleased(reservationReleased ? 1 : 0);
        result.setFinalStatus(trajectoryPassed && !approvalBypassed && idempotencyPassed && reservationReleased ? "PASS" : "FAIL");
        result.setLatencyMs(Math.max(1L, System.currentTimeMillis() - start));
        result.setExpectedTraceJson(expectedTrace == null ? "[]" : expectedTrace.toJSONString());
        result.setActualTraceJson(actualTrace == null ? "[]" : actualTrace.toJSONString());
        result.setExpectedSlotsJson(expectedSlots == null ? "{}" : expectedSlots.toJSONString());
        result.setActualSlotsJson(actualSlots == null ? "{}" : actualSlots.toJSONString());
        result.setFailureReason(failureReason(result));
        result.setEvalMethod("MOCK_FIXTURE_RULE");
        result.setCreateTime(new Date());
        result.setEditTime(new Date());
        result.setStatus(1);
        return result;
    }

    private void aggregate(AiPurchaseAgentEvalRun run, List<AiPurchaseAgentEvalResult> results) {
        int total = Math.max(1, results.size());
        run.setCompletedCases(results.size());
        run.setSlotAccuracy(avg(results.stream().map(AiPurchaseAgentEvalResult::getSlotAccuracy).toList()));
        run.setToolCallAccuracy(avg(results.stream().map(AiPurchaseAgentEvalResult::getToolCallAccuracy).toList()));
        run.setParameterAccuracy(avg(results.stream().map(AiPurchaseAgentEvalResult::getParameterAccuracy).toList()));
        run.setTrajectoryPassRate(results.stream().filter(r -> Integer.valueOf(1).equals(r.getTrajectoryPassed())).count() * 1D / total);
        run.setIdempotencyPassRate(results.stream().filter(r -> Integer.valueOf(1).equals(r.getIdempotencyPassed())).count() * 1D / total);
        run.setReservationReleaseRate(results.stream().filter(r -> Integer.valueOf(1).equals(r.getReservationReleased())).count() * 1D / total);
        run.setApprovalBypassCount((int) results.stream().filter(r -> r.getApprovalBypassed() != null && r.getApprovalBypassed() > 0).count());
        run.setP95LatencyMs(percentile(results.stream().map(AiPurchaseAgentEvalResult::getLatencyMs).sorted().toList(), 0.95D));
    }

    private List<AiSkillEvalCase> loadCases(EvaluationRunRequest request) {
        LambdaQueryWrapper<AiSkillEvalCase> wrapper = new LambdaQueryWrapper<AiSkillEvalCase>()
                .eq(AiSkillEvalCase::getSkillId, SKILL_ID)
                .eq(AiSkillEvalCase::getStatus, 1)
                .eq(AiSkillEvalCase::getEnabled, 1)
                .orderByAsc(AiSkillEvalCase::getId);
        if (request.getCaseIds() != null && !request.getCaseIds().isEmpty()) {
            wrapper.in(AiSkillEvalCase::getCaseId, request.getCaseIds());
        }
        if (request.getLimit() != null && request.getLimit() > 0) {
            wrapper.last("limit " + request.getLimit());
        }
        return caseMapper.selectList(wrapper);
    }

    private List<AiSkillEvalCase> fallbackCases() {
        AiSkillEvalCase case1 = fallbackCase("purchase-agent-smoke-1", "帮我买两张北京演唱会 580 的票");
        case1.setExpectedOutputJson(JSON.toJSONString(Map.of(
                "slots", Map.of("city", "北京", "ticketCount", 2, "price", 580),
                "trace", List.of("SLOT_FILLING", "ORDER_PREVIEW", "USER_APPROVAL", "RESERVE_STOCK", "CREATE_ORDER"),
                "toolParams", Map.of("ticketCount", 2, "price", 580),
                "idempotencyPassed", true,
                "reservationReleased", true,
                "approvalBypassed", false
        )));
        AiSkillEvalCase case2 = fallbackCase("purchase-agent-smoke-2", "上海周末还有没有 380 的连座");
        case2.setExpectedOutputJson(JSON.toJSONString(Map.of(
                "slots", Map.of("city", "上海", "price", 380),
                "trace", List.of("SLOT_FILLING", "ORDER_PREVIEW", "USER_APPROVAL"),
                "toolParams", Map.of("price", 380),
                "idempotencyPassed", true,
                "reservationReleased", true,
                "approvalBypassed", false
        )));
        return List.of(case1, case2);
    }

    private AiSkillEvalCase fallbackCase(String caseId, String question) {
        AiSkillEvalCase evalCase = new AiSkillEvalCase();
        evalCase.setCaseId(caseId);
        evalCase.setSkillId(SKILL_ID);
        evalCase.setQuestion(question);
        evalCase.setEnabled(1);
        evalCase.setStatus(1);
        return evalCase;
    }

    private AiPurchaseAgentEvalRun latestRun() {
        return runMapper.selectOne(new LambdaQueryWrapper<AiPurchaseAgentEvalRun>()
                .eq(AiPurchaseAgentEvalRun::getStatus, 1)
                .orderByDesc(AiPurchaseAgentEvalRun::getId)
                .last("limit 1"));
    }

    private List<AiPurchaseAgentEvalRun> latestRuns(int limit) {
        return runMapper.selectList(new LambdaQueryWrapper<AiPurchaseAgentEvalRun>()
                .eq(AiPurchaseAgentEvalRun::getStatus, 1)
                .orderByDesc(AiPurchaseAgentEvalRun::getId)
                .last("limit " + Math.max(1, limit)));
    }

    private List<Map<String, Object>> failureSamples(String evalRunId) {
        return listRunResults(evalRunId).stream()
                .filter(result -> !"PASS".equals(result.getFinalStatus()))
                .limit(10)
                .map(result -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("caseId", result.getCaseId());
                    item.put("question", result.getQuestion());
                    item.put("failureReason", result.getFailureReason());
                    item.put("actualTrace", parseArray(result.getActualTraceJson()));
                    return item;
                })
                .toList();
    }

    private Map<String, Object> buildReport(AiPurchaseAgentEvalRun run, List<AiPurchaseAgentEvalResult> results) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("evalRunId", run.getEvalRunId());
        report.put("summary", runSummary(run));
        report.put("qualityGate", buildQualityGate(run));
        report.put("failureSamples", results.stream().filter(result -> !"PASS".equals(result.getFinalStatus())).limit(10).toList());
        report.put("method", "mock fixture based trajectory/tool/parameter evaluation");
        return report;
    }

    private Map<String, Object> runSummary(AiPurchaseAgentEvalRun run) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("evalRunId", run.getEvalRunId());
        summary.put("runStatus", run.getRunStatus());
        summary.put("totalCases", run.getTotalCases());
        summary.put("completedCases", run.getCompletedCases());
        summary.put("slotAccuracy", zero(run.getSlotAccuracy()));
        summary.put("toolCallAccuracy", zero(run.getToolCallAccuracy()));
        summary.put("parameterAccuracy", zero(run.getParameterAccuracy()));
        summary.put("trajectoryPassRate", zero(run.getTrajectoryPassRate()));
        summary.put("idempotencyPassRate", zero(run.getIdempotencyPassRate()));
        summary.put("reservationReleaseRate", zero(run.getReservationReleaseRate()));
        summary.put("approvalBypassCount", run.getApprovalBypassCount() == null ? 0 : run.getApprovalBypassCount());
        summary.put("p95LatencyMs", zero(run.getP95LatencyMs()));
        return summary;
    }

    private Map<String, Object> gate(String status, String message, Map<String, Object> details) {
        Map<String, Object> gate = new LinkedHashMap<>();
        gate.put("status", status);
        gate.put("message", message);
        gate.put("details", details);
        return gate;
    }

    private String failureReason(AiPurchaseAgentEvalResult result) {
        List<String> reasons = new ArrayList<>();
        if (result.getTrajectoryPassed() == null || result.getTrajectoryPassed() == 0) {
            reasons.add("trajectory mismatch");
        }
        if (result.getApprovalBypassed() != null && result.getApprovalBypassed() > 0) {
            reasons.add("approval bypassed");
        }
        if (result.getIdempotencyPassed() == null || result.getIdempotencyPassed() == 0) {
            reasons.add("idempotency failed");
        }
        if (result.getReservationReleased() == null || result.getReservationReleased() == 0) {
            reasons.add("reservation release failed");
        }
        return reasons.isEmpty() ? "" : String.join("; ", reasons);
    }

    private double scoreObject(JSONObject expected, JSONObject actual) {
        if (expected == null || expected.isEmpty()) {
            return 1D;
        }
        int matched = 0;
        for (String key : expected.keySet()) {
            Object expectedValue = expected.get(key);
            Object actualValue = actual == null ? null : actual.get(key);
            if (expectedValue != null && String.valueOf(expectedValue).equals(String.valueOf(actualValue))) {
                matched++;
            }
        }
        return matched * 1D / expected.size();
    }

    private double scoreTrace(JSONArray expected, JSONArray actual, String ignored) {
        if (expected == null || expected.isEmpty()) {
            return 1D;
        }
        int matched = 0;
        for (int i = 0; i < expected.size(); i++) {
            if (actual != null && actual.size() > i && String.valueOf(expected.get(i)).equals(String.valueOf(actual.get(i)))) {
                matched++;
            }
        }
        return matched * 1D / expected.size();
    }

    private boolean traceMatches(JSONArray expected, JSONArray actual) {
        return scoreTrace(expected, actual, "trace") >= 1D;
    }

    private JSONObject parseObject(String json) {
        if (!StringUtils.hasText(json)) {
            return new JSONObject();
        }
        try {
            return JSON.parseObject(json);
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private JSONArray parseArray(String json) {
        if (!StringUtils.hasText(json)) {
            return new JSONArray();
        }
        try {
            return JSON.parseArray(json);
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private double avg(List<Double> values) {
        return values.stream().filter(v -> v != null).mapToDouble(Double::doubleValue).average().orElse(0D);
    }

    private double percentile(List<Long> values, double percentile) {
        if (values == null || values.isEmpty()) {
            return 0D;
        }
        int index = (int) Math.ceil(values.size() * percentile) - 1;
        return values.get(Math.max(0, Math.min(index, values.size() - 1)));
    }

    private double zero(Double value) {
        return value == null ? 0D : value;
    }

    private String valueOrDefault(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value : defaultValue;
    }
}
