package org.javaup.ai.assistant.skill.ops;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AiOpsFaultInjectionService {

    private static final String DEFAULT_SERVICE = "order-service";

    public List<AiOpsFaultScenario> listScenarios() {
        return List.of(
                scenario("high-latency", "高延迟", "接口 P95 延迟持续升高，适合验证指标 range query 和慢请求定位。",
                        "gateway-service", "SEV2",
                        Map.of("latencyP95Ms", 2400, "errorRate", 0.01, "traceId", "latency-trace-001"),
                        List.of("upstream timeout", "thread pool saturation"),
                        List.of("按 traceId 查看最慢 span", "检查最近发布、线程池和下游 RT")),
                scenario("error-spike", "错误率升高", "5xx 与 ERROR 日志同时升高，适合验证日志聚合和 SLO burn 判断。",
                        "order-service", "SEV1",
                        Map.of("errorRate", 0.18, "errorLogs", 56, "traceId", "error-trace-002"),
                        List.of("application error spike", "downstream failure"),
                        List.of("按异常类型聚合日志", "回看最近发布和配置变更", "临时降级异常下游")),
                scenario("db-slow-query", "DB 慢查询", "订单链路出现慢 SQL，适合验证 DB cost guard 与 trace 证据链。",
                        "order-service", "SEV2",
                        Map.of("slowSqlMs", 3800, "dbRowsScanned", 240000, "traceId", "db-trace-003"),
                        List.of("DB slow query", "missing index or bad query plan"),
                        List.of("执行 EXPLAIN 并确认索引命中", "限制扫描行数", "核对近期 SQL/索引变更")),
                scenario("downstream-unavailable", "下游不可用", "支付或库存服务不可用，适合验证跨服务 trace 和依赖边界。",
                        "payment-service", "SEV1",
                        Map.of("availability", 0.82, "connectTimeouts", 41, "traceId", "downstream-trace-004"),
                        List.of("downstream unavailable", "network or dependency outage"),
                        List.of("确认依赖服务健康", "启用重试/熔断策略", "通知业务侧降级")),
                scenario("inventory-order-abnormal", "库存/订单链路异常", "库存扣减与订单创建状态不一致，适合验证业务链路 RCA。",
                        "inventory-service", "SEV1",
                        Map.of("stockMismatchCount", 17, "orderRollbackCount", 12, "traceId", "inventory-trace-005"),
                        List.of("inventory/order consistency anomaly", "transaction or message delay"),
                        List.of("核对订单与库存消息堆积", "检查幂等键和补偿任务", "暂停异常活动流量")));
    }

    public AiOpsFaultScenario getScenario(String scenarioId) {
        return listScenarios().stream()
                .filter(scenario -> scenario.getScenarioId().equals(scenarioId))
                .findFirst()
                .orElse(null);
    }

    public Map<String, Object> injectScenario(String scenarioId, OpsRcaRequest request) {
        AiOpsFaultScenario scenario = getScenario(scenarioId);
        if (scenario == null) {
            return Map.of("status", "NOT_FOUND", "scenarioId", scenarioId);
        }
        OpsRcaRequest normalized = normalizeRequest(scenario, request);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("status", "SIMULATED");
        evidence.put("scenario", scenario);
        evidence.put("request", Map.of(
                "query", normalized.getQuery(),
                "serviceName", normalized.getServiceName(),
                "traceId", normalized.getTraceId(),
                "spanId", normalized.getSpanId(),
                "windowMinutes", normalized.getWindowMinutes()));
        evidence.put("timeWindow", Map.of(
                "end", Instant.now().toString(),
                "minutes", normalized.getWindowMinutes()));
        evidence.put("evidenceBundle", simulatedEvidenceBundle(scenario, normalized));
        evidence.put("assistantPrompt", buildAssistantPrompt(scenario, normalized));
        evidence.put("safety", Map.of(
                "simulationOnly", true,
                "writesBusinessData", false,
                "requiresAdmin", true));
        return evidence;
    }

    private AiOpsFaultScenario scenario(String scenarioId,
                                        String name,
                                        String description,
                                        String serviceName,
                                        String severity,
                                        Map<String, Object> signals,
                                        List<String> expectedCauses,
                                        List<String> suggestedActions) {
        String traceId = String.valueOf(signals.getOrDefault("traceId", scenarioId + "-trace"));
        return AiOpsFaultScenario.builder()
                .scenarioId(scenarioId)
                .name(name)
                .description(description)
                .serviceName(serviceName)
                .windowMinutes(30)
                .severity(severity)
                .rcaPrompt("请诊断 " + serviceName + " 最近 30 分钟的 " + name + "，traceId=" + traceId)
                .injectedSignals(signals)
                .expectedEvidence(Map.of(
                        "logs", logEvidence(scenarioId, serviceName, signals),
                        "metrics", metricsEvidence(scenarioId, serviceName, signals),
                        "trace", Map.of("traceId", traceId, "serviceName", serviceName, "firstBadSpan", serviceName)))
                .expectedCauses(expectedCauses)
                .suggestedActions(suggestedActions)
                .build();
    }

    private OpsRcaRequest normalizeRequest(AiOpsFaultScenario scenario, OpsRcaRequest request) {
        OpsRcaRequest normalized = new OpsRcaRequest();
        normalized.setServiceName(StringUtils.hasText(request == null ? null : request.getServiceName())
                ? request.getServiceName()
                : scenario.getServiceName());
        normalized.setWindowMinutes(request != null && request.getWindowMinutes() != null && request.getWindowMinutes() > 0
                ? Math.min(24 * 60, request.getWindowMinutes())
                : scenario.getWindowMinutes());
        normalized.setTraceId(StringUtils.hasText(request == null ? null : request.getTraceId())
                ? request.getTraceId()
                : String.valueOf(scenario.getInjectedSignals().getOrDefault("traceId", "")));
        normalized.setSpanId(StringUtils.hasText(request == null ? null : request.getSpanId())
                ? request.getSpanId()
                : scenario.getScenarioId() + "-span-001");
        normalized.setQuery(StringUtils.hasText(request == null ? null : request.getQuery())
                ? request.getQuery()
                : scenario.getRcaPrompt());
        normalized.setReleaseVersion(StringUtils.hasText(request == null ? null : request.getReleaseVersion())
                ? request.getReleaseVersion()
                : scenario.getScenarioId() + "-release-20260630");
        normalized.setConfigKey(StringUtils.hasText(request == null ? null : request.getConfigKey())
                ? request.getConfigKey()
                : scenario.getScenarioId() + ".feature-flag");
        normalized.setChangeWindowMinutes(request != null && request.getChangeWindowMinutes() != null && request.getChangeWindowMinutes() > 0
                ? Math.min(7 * 24 * 60, request.getChangeWindowMinutes())
                : Math.max(60, normalized.getWindowMinutes() * 2));
        return normalized;
    }

    private Map<String, Object> simulatedEvidenceBundle(AiOpsFaultScenario scenario, OpsRcaRequest request) {
        Map<String, Object> slo = simulatedSlo(scenario, request);
        Map<String, Object> alertContext = simulatedAlertContext(scenario, request, slo);
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("serviceName", request.getServiceName());
        bundle.put("traceId", request.getTraceId());
        bundle.put("spanId", request.getSpanId());
        bundle.put("scenarioId", scenario.getScenarioId());
        bundle.put("severity", scenario.getSeverity());
        Map<String, Object> logAggregation = simulatedLogAggregation(scenario, request);
        bundle.put("signals", Map.of(
                "logs", scenario.getExpectedEvidence().get("logs"),
                "metrics", scenario.getExpectedEvidence().get("metrics"),
                "trace", scenario.getExpectedEvidence().get("trace"),
                "logAggregation", logAggregation,
                "traceSummary", simulatedTraceSummary(scenario, request)));
        bundle.put("serviceTopology", simulatedTopology(scenario, request));
        bundle.put("recentChanges", simulatedRecentChanges(scenario, request));
        bundle.put("evidenceTimeline", simulatedTimeline(scenario, request));
        bundle.put("slo", slo);
        bundle.put("alertContext", alertContext);
        bundle.put("signalCorrelation", simulatedSignalCorrelation(scenario, request, slo, logAggregation));
        bundle.put("playbookHints", simulatedPlaybookHints(scenario));
        bundle.put("suspectedCause", scenario.getExpectedCauses().get(0));
        bundle.put("confidence", "HIGH");
        bundle.put("evidenceItems", List.of(
                "scenario=" + scenario.getScenarioId(),
                "service=" + request.getServiceName(),
                "traceId=" + request.getTraceId(),
                "spanId=" + request.getSpanId()));
        bundle.put("suggestedActions", scenario.getSuggestedActions());
        bundle.put("rcaSummary", Map.of(
                "primarySignal", "scenario=" + scenario.getScenarioId(),
                "suspectService", request.getServiceName(),
                "firstFailingService", request.getServiceName(),
                "firstFailingSpanId", request.getSpanId(),
                "changeRisk", "HIGH",
                "sloSeverity", slo.get("severity"),
                "correlationCoverageScore", 1.0,
                "alertTriggered", alertContext.get("triggered"),
                "requiresHumanReview", true));
        return bundle;
    }

    private Map<String, Object> simulatedSlo(AiOpsFaultScenario scenario, OpsRcaRequest request) {
        double burnRate = switch (scenario.getSeverity()) {
            case "SEV1" -> 4.8;
            case "SEV2" -> 2.3;
            default -> 1.2;
        };
        Object errorLogs = scenario.getInjectedSignals().get("errorLogs");
        if (errorLogs instanceof Number number) {
            burnRate = Math.max(burnRate, Math.round(number.doubleValue() / 12.0 * 100.0) / 100.0);
        }
        Object errorRate = scenario.getInjectedSignals().get("errorRate");
        if (errorRate instanceof Number number) {
            burnRate = Math.max(burnRate, Math.round(number.doubleValue() * 25.0 * 100.0) / 100.0);
        }
        String severity = burnRate >= 4.0 ? "CRITICAL" : burnRate >= 2.0 ? "HIGH" : "MEDIUM";
        Map<String, Object> slo = new LinkedHashMap<>();
        slo.put("availabilityTarget", 0.995);
        slo.put("errorBudgetBurning", true);
        slo.put("burnRate", burnRate);
        slo.put("severity", severity);
        slo.put("errorBudgetRemaining", Math.max(0.0, Math.round((1.0 - Math.min(0.95, burnRate * 0.18)) * 100.0) / 100.0));
        slo.put("alertTriggered", true);
        slo.put("alertName", "DamaiAiFaultInjectionSloBurn");
        slo.put("windowMinutes", request.getWindowMinutes());
        slo.put("recommendedPolicy", "page on-call, freeze risky changes, and verify mitigation against simulated signals");
        slo.put("reason", "simulated fault scenario breaches SLO burn-rate policy");
        return slo;
    }

    private Map<String, Object> simulatedAlertContext(AiOpsFaultScenario scenario,
                                                      OpsRcaRequest request,
                                                      Map<String, Object> slo) {
        return Map.of(
                "triggered", true,
                "name", slo.get("alertName"),
                "severity", slo.get("severity"),
                "serviceName", request.getServiceName(),
                "windowMinutes", request.getWindowMinutes(),
                "primarySignal", "scenario=" + scenario.getScenarioId(),
                "routingHint", "oncall:" + request.getServiceName(),
                "labels", Map.of(
                        "service", request.getServiceName(),
                        "scenario", scenario.getScenarioId(),
                        "traceId", request.getTraceId(),
                        "spanId", request.getSpanId()));
    }

    private List<String> simulatedPlaybookHints(AiOpsFaultScenario scenario) {
        return List.of(
                "fault-simulation-playbook: compare expected causes with generated RCA before closing drill",
                "slo-playbook: track burn-rate recovery after mitigation and capture final evidence",
                "release-playbook: verify recent release/config overlap before rollback",
                "owner-handoff: assign " + scenario.getServiceName() + " owner with trace and metric evidence");
    }

    private Map<String, Object> simulatedLogAggregation(AiOpsFaultScenario scenario, OpsRcaRequest request) {
        Object errorLogs = scenario.getInjectedSignals().getOrDefault("errorLogs", scenario.getSeverity().equals("SEV1") ? 56 : 8);
        int errorCount = errorLogs instanceof Number number ? number.intValue() : 8;
        return Map.of(
                "totalLogs", errorCount + 2,
                "byLevel", Map.of("ERROR", errorCount, "WARN", 2),
                "byService", Map.of(request.getServiceName(), errorCount + 2),
                "bySpanId", Map.of(request.getSpanId(), Math.max(1, errorCount)),
                "topFingerprints", List.of(Map.of(
                        "key", scenario.getScenarioId() + " simulated failure in " + request.getServiceName(),
                        "count", errorCount)),
                "aggregationMode", "fault-simulation");
    }

    private Map<String, Object> simulatedSignalCorrelation(AiOpsFaultScenario scenario,
                                                           OpsRcaRequest request,
                                                           Map<String, Object> slo,
                                                           Map<String, Object> logAggregation) {
        Map<String, Object> correlation = new LinkedHashMap<>();
        correlation.put("serviceLabels", Map.of(
                "service", request.getServiceName(),
                "traceId", request.getTraceId(),
                "spanId", request.getSpanId(),
                "scenario", scenario.getScenarioId()));
        correlation.put("timeWindowMinutes", request.getWindowMinutes());
        correlation.put("coverageScore", 1.0);
        correlation.put("linkedSignals", List.of("logs", "trace", "metrics", "promql-range", "recent-changes"));
        correlation.put("logAggregation", logAggregation);
        correlation.put("metricStatus", String.valueOf(((Map<?, ?>) scenario.getExpectedEvidence().get("metrics")).get("healthStatus")));
        correlation.put("topologySource", "fault-simulation");
        correlation.put("changeRisk", "HIGH");
        correlation.put("sloSeverity", slo.get("severity"));
        correlation.put("evidenceCompleteness", "STRONG");
        correlation.put("explanation", "simulated RCA links logs, metrics, trace/span, topology, SLO, and recent changes");
        return correlation;
    }

    private Map<String, Object> simulatedTopology(AiOpsFaultScenario scenario, OpsRcaRequest request) {
        List<String> services = switch (scenario.getScenarioId()) {
            case "downstream-unavailable" -> List.of("gateway-service", "order-service", "payment-service");
            case "inventory-order-abnormal" -> List.of("gateway-service", "order-service", "inventory-service");
            case "db-slow-query" -> List.of("gateway-service", "order-service", "mysql");
            default -> List.of("gateway-service", request.getServiceName(), "payment-service");
        };
        return Map.of(
                "rootService", services.get(0),
                "suspectService", request.getServiceName(),
                "impactedServices", services,
                "dependencyEdges", List.of(
                        Map.of("from", services.get(0), "to", services.get(1), "relation", "entry-call"),
                        Map.of("from", services.get(1), "to", services.get(2), "relation", "downstream-call")),
                "source", "fault-simulation");
    }

    private Map<String, Object> simulatedTraceSummary(AiOpsFaultScenario scenario, OpsRcaRequest request) {
        return Map.of(
                "traceId", request.getTraceId(),
                "requestedSpanId", request.getSpanId(),
                "spanCount", 3,
                "firstFailingService", request.getServiceName(),
                "firstFailingSpanId", request.getSpanId(),
                "path", List.of(
                        Map.of("order", 0, "service", "gateway-service", "spanId", scenario.getScenarioId() + "-span-000", "level", "INFO", "summary", "request accepted"),
                        Map.of("order", 1, "service", request.getServiceName(), "spanId", request.getSpanId(), "level", "ERROR", "summary", scenario.getScenarioId() + " detected"),
                        Map.of("order", 2, "service", "payment-service", "spanId", scenario.getScenarioId() + "-span-002", "level", "WARN", "summary", "downstream degradation propagated")));
    }

    private Map<String, Object> simulatedRecentChanges(AiOpsFaultScenario scenario, OpsRcaRequest request) {
        return Map.of(
                "window", Map.of("minutes", request.getChangeWindowMinutes()),
                "items", List.of(
                        Map.of(
                                "type", "release",
                                "serviceName", request.getServiceName(),
                                "version", request.getReleaseVersion(),
                                "risk", "HIGH",
                                "summary", "simulated release overlaps with injected incident"),
                        Map.of(
                                "type", "config",
                                "serviceName", request.getServiceName(),
                                "configKey", request.getConfigKey(),
                                "risk", "MEDIUM",
                                "summary", "simulated config flag should be checked before mitigation")),
                "riskLevel", "HIGH",
                "reviewHint", "diff release/config against the first failing service before rollback");
    }

    private List<Map<String, Object>> simulatedTimeline(AiOpsFaultScenario scenario, OpsRcaRequest request) {
        return List.of(
                Map.of("kind", "change", "service", request.getServiceName(), "summary", "release " + request.getReleaseVersion()),
                Map.of("kind", "metric", "service", request.getServiceName(), "summary", scenario.getName() + " metric breached"),
                Map.of("kind", "trace", "service", request.getServiceName(), "summary", "first failing span traceId=" + request.getTraceId() + " spanId=" + request.getSpanId()),
                Map.of("kind", "log", "service", request.getServiceName(), "summary", scenario.getExpectedCauses().get(0)));
    }

    private String buildAssistantPrompt(AiOpsFaultScenario scenario, OpsRcaRequest request) {
        return "请基于模拟故障证据诊断 " + request.getServiceName()
                + " 最近 " + request.getWindowMinutes() + " 分钟的 " + scenario.getName()
                + "，必须输出日志、指标、trace、span、SLO、告警上下文和处置建议，traceId=" + request.getTraceId()
                + " spanId=" + request.getSpanId();
    }

    private Map<String, Object> logEvidence(String scenarioId, String serviceName, Map<String, Object> signals) {
        return Map.of(
                "serviceName", serviceName,
                "count", signals.getOrDefault("errorLogs", scenarioId.equals("error-spike") ? 56 : 8),
                "samples", List.of(
                        Map.of("level", "ERROR", "message", scenarioId + " simulated failure in " + serviceName),
                        Map.of("level", "WARN", "message", "SLO burn detected for " + serviceName)));
    }

    private Map<String, Object> metricsEvidence(String scenarioId, String serviceName, Map<String, Object> signals) {
        return Map.of(
                "serviceName", serviceName,
                "healthStatus", scenarioId.toUpperCase().replace('-', '_') + "_ALERT",
                "series", signals);
    }
}
