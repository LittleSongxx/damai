package org.javaup.ai.assistant.skill.ops;

import lombok.RequiredArgsConstructor;
import org.javaup.ai.assistant.gateway.LogGateway;
import org.javaup.ai.assistant.gateway.MetricsGateway;
import org.javaup.ai.assistant.gateway.TraceGateway;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class OpsRcaEvidenceService {

    private static final Pattern SERVICE_PATTERN = Pattern.compile("([a-z][a-z0-9-]+-service)");
    private static final Pattern TRACE_PATTERN = Pattern.compile("trace(?:id)?[=: ]+([A-Za-z0-9\\-_]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SPAN_PATTERN = Pattern.compile("span(?:id)?[=: ]+([A-Za-z0-9\\-_]+)", Pattern.CASE_INSENSITIVE);

    private final LogGateway logGateway;
    private final MetricsGateway metricsGateway;
    private final TraceGateway traceGateway;

    public Map<String, Object> buildEvidenceBundle(OpsRcaRequest request) {
        OpsRcaRequest normalized = normalize(request);
        Instant end = Instant.now();
        Instant start = end.minus(Duration.ofMinutes(normalized.getWindowMinutes()));

        Map<String, Object> logs = safeMap(() -> logGateway.searchLogsByKeyword(
                normalized.getQuery(),
                normalized.getServiceName(),
                "ERROR",
                30));
        Map<String, Object> warnings = safeMap(() -> logGateway.searchLogsByKeyword(
                normalized.getQuery(),
                normalized.getServiceName(),
                "WARN",
                20));
        Map<String, Object> metrics = safeMap(() -> metricsGateway.getServiceHealthOverview(normalized.getServiceName()));
        Map<String, Object> trace = StringUtils.hasText(normalized.getTraceId())
                ? safeMap(() -> traceGateway.getTrace(normalized.getTraceId()))
                : Map.of("traceId", "", "count", 0, "logs", List.of());
        Map<String, Object> latencyRange = safeMap(() -> metricsGateway.queryRange(
                "histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{application=\""
                        + normalized.getServiceName() + "\"}[5m])) by (le))",
                start,
                end,
                Duration.ofMinutes(1)));
        Map<String, Object> errorRateRange = safeMap(() -> metricsGateway.queryRange(
                "sum(rate(http_server_requests_seconds_count{application=\"" + normalized.getServiceName()
                        + "\",status=~\"5..\"}[5m]))",
                start,
                end,
                Duration.ofMinutes(1)));
        Map<String, Object> serviceTopology = buildServiceTopology(normalized, logs, warnings, trace);
        Map<String, Object> recentChanges = buildRecentChanges(normalized, start, end, serviceTopology);
        List<Map<String, Object>> evidenceTimeline = buildEvidenceTimeline(logs, warnings, trace, recentChanges);
        Map<String, Object> traceSummary = buildTraceSummary(trace, serviceTopology, normalized);
        Map<String, Object> logAggregation = buildLogAggregation(logs, warnings, trace);

        List<String> evidenceItems = new ArrayList<>();
        int errorCount = intValue(logs.get("count"));
        int warnCount = intValue(warnings.get("count"));
        int traceLogCount = intValue(trace.get("count"));
        String healthStatus = String.valueOf(metrics.getOrDefault("healthStatus", "UNKNOWN"));
        if (errorCount > 0) {
            evidenceItems.add("error_logs=" + errorCount);
        }
        if (warnCount > 0) {
            evidenceItems.add("warn_logs=" + warnCount);
        }
        if (traceLogCount > 0) {
            evidenceItems.add("trace_logs=" + traceLogCount);
        }
        if (!"HEALTHY".equalsIgnoreCase(healthStatus)) {
            evidenceItems.add("health_status=" + healthStatus);
        }

        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("serviceName", normalized.getServiceName());
        bundle.put("traceId", normalized.getTraceId() == null ? "" : normalized.getTraceId());
        bundle.put("spanId", normalized.getSpanId() == null ? "" : normalized.getSpanId());
        bundle.put("timeWindow", Map.of(
                "start", start.toString(),
                "end", end.toString(),
                "minutes", normalized.getWindowMinutes()));
        bundle.put("signals", Map.of(
                "logs", logs,
                "warnings", warnings,
                "metrics", metrics,
                "trace", trace,
                "traceSummary", traceSummary,
                "logAggregation", logAggregation,
                "latencyP95Range", latencyRange,
                "errorRateRange", errorRateRange));
        bundle.put("serviceTopology", serviceTopology);
        bundle.put("recentChanges", recentChanges);
        bundle.put("evidenceTimeline", evidenceTimeline);
        Map<String, Object> slo = buildSloSummary(errorCount, warnCount, traceLogCount, healthStatus, normalized.getWindowMinutes());
        Map<String, Object> alertContext = buildAlertContext(normalized, slo, evidenceItems);
        List<String> playbookHints = playbookHints(slo, healthStatus, errorCount, traceLogCount, recentChanges);
        Map<String, Object> signalCorrelation = buildSignalCorrelation(normalized, logs, warnings, metrics, trace,
                latencyRange, errorRateRange, serviceTopology, recentChanges, logAggregation, slo);
        bundle.put("slo", slo);
        bundle.put("alertContext", alertContext);
        bundle.put("signalCorrelation", signalCorrelation);
        bundle.put("playbookHints", playbookHints);
        bundle.put("suspectedCause", suspectedCause(healthStatus, errorCount, warnCount, traceLogCount));
        bundle.put("confidence", confidence(errorCount, warnCount, traceLogCount, healthStatus, recentChanges));
        bundle.put("evidenceItems", evidenceItems);
        bundle.put("suggestedActions", suggestedActions(healthStatus, errorCount, traceLogCount, slo));
        bundle.put("rcaSummary", Map.of(
                "primarySignal", evidenceItems.isEmpty() ? "no strong local anomaly" : evidenceItems.get(0),
                "suspectService", serviceTopology.get("suspectService"),
                "firstFailingService", traceSummary.get("firstFailingService"),
                "firstFailingSpanId", traceSummary.get("firstFailingSpanId"),
                "changeRisk", recentChanges.get("riskLevel"),
                "sloSeverity", slo.get("severity"),
                "correlationCoverageScore", signalCorrelation.get("coverageScore"),
                "alertTriggered", alertContext.get("triggered"),
                "requiresHumanReview", !"LOW".equals(confidence(errorCount, warnCount, traceLogCount, healthStatus, recentChanges))));
        return bundle;
    }

    private OpsRcaRequest normalize(OpsRcaRequest request) {
        OpsRcaRequest normalized = new OpsRcaRequest();
        String query = request == null || !StringUtils.hasText(request.getQuery()) ? "" : request.getQuery();
        normalized.setQuery(query);
        normalized.setServiceName(StringUtils.hasText(request != null ? request.getServiceName() : null)
                ? request.getServiceName()
                : firstMatch(query, SERVICE_PATTERN, "damai-ai"));
        normalized.setTraceId(StringUtils.hasText(request != null ? request.getTraceId() : null)
                ? request.getTraceId()
                : firstMatch(query, TRACE_PATTERN, ""));
        normalized.setSpanId(StringUtils.hasText(request != null ? request.getSpanId() : null)
                ? request.getSpanId()
                : firstMatch(query, SPAN_PATTERN, ""));
        int minutes = request != null && request.getWindowMinutes() != null && request.getWindowMinutes() > 0
                ? request.getWindowMinutes()
                : 30;
        normalized.setWindowMinutes(Math.min(24 * 60, Math.max(5, minutes)));
        normalized.setReleaseVersion(StringUtils.hasText(request != null ? request.getReleaseVersion() : null)
                ? request.getReleaseVersion()
                : firstMatch(query, Pattern.compile("(?:release|version|版本)[=: ]+([A-Za-z0-9._\\-]+)", Pattern.CASE_INSENSITIVE), ""));
        normalized.setConfigKey(StringUtils.hasText(request != null ? request.getConfigKey() : null)
                ? request.getConfigKey()
                : firstMatch(query, Pattern.compile("(?:config|配置)[=: ]+([A-Za-z0-9._\\-]+)", Pattern.CASE_INSENSITIVE), ""));
        int changeMinutes = request != null && request.getChangeWindowMinutes() != null && request.getChangeWindowMinutes() > 0
                ? request.getChangeWindowMinutes()
                : Math.max(60, normalized.getWindowMinutes() * 2);
        normalized.setChangeWindowMinutes(Math.min(7 * 24 * 60, Math.max(15, changeMinutes)));
        return normalized;
    }

    private Map<String, Object> buildSloSummary(int errorCount,
                                                int warnCount,
                                                int traceLogCount,
                                                String healthStatus,
                                                int windowMinutes) {
        double availabilityTarget = 0.995;
        boolean unhealthy = !"HEALTHY".equalsIgnoreCase(healthStatus) && !"UNKNOWN".equalsIgnoreCase(healthStatus);
        double normalizedWindow = Math.max(5.0, windowMinutes) / 30.0;
        double burnRate = round(Math.max(errorCount / 10.0, traceLogCount / 4.0) / normalizedWindow
                + (unhealthy ? 1.5 : 0.0)
                + Math.min(1.0, warnCount / 20.0));
        boolean burning = burnRate >= 1.0 || unhealthy;
        String severity;
        if (burnRate >= 6.0 || errorCount >= 50) {
            severity = "CRITICAL";
        } else if (burnRate >= 2.0 || errorCount >= 10 || unhealthy) {
            severity = "HIGH";
        } else if (burnRate >= 1.0 || warnCount >= 10) {
            severity = "MEDIUM";
        } else {
            severity = "LOW";
        }
        double errorBudgetRemaining = burning
                ? Math.max(0.0, round(1.0 - Math.min(0.95, burnRate * 0.18)))
                : 1.0;
        Map<String, Object> slo = new LinkedHashMap<>();
        slo.put("availabilityTarget", availabilityTarget);
        slo.put("errorBudgetBurning", burning);
        slo.put("burnRate", burnRate);
        slo.put("severity", severity);
        slo.put("errorBudgetRemaining", errorBudgetRemaining);
        slo.put("alertTriggered", burning);
        slo.put("alertName", "DamaiAiSloBurnRate");
        slo.put("windowMinutes", windowMinutes);
        slo.put("recommendedPolicy", switch (severity) {
            case "CRITICAL", "HIGH" -> "page on-call and freeze risky release/config changes";
            case "MEDIUM" -> "notify service owner and monitor the next burn window";
            default -> "keep observing; no SLO action required";
        });
        slo.put("reason", burning
                ? "recent errors, traces, or unhealthy metrics indicate SLO error budget burn"
                : "no local SLO burn threshold breach detected");
        return slo;
    }

    private Map<String, Object> buildAlertContext(OpsRcaRequest request,
                                                  Map<String, Object> slo,
                                                  List<String> evidenceItems) {
        boolean triggered = Boolean.TRUE.equals(slo.get("alertTriggered"));
        String severity = stringValue(slo.get("severity"));
        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("triggered", triggered);
        alert.put("name", slo.get("alertName"));
        alert.put("severity", severity);
        alert.put("serviceName", request.getServiceName());
        alert.put("windowMinutes", request.getWindowMinutes());
        alert.put("primarySignal", evidenceItems.isEmpty() ? "slo=" + severity : evidenceItems.get(0));
        alert.put("routingHint", triggered && ("CRITICAL".equals(severity) || "HIGH".equals(severity))
                ? "oncall:" + request.getServiceName()
                : "owner:" + request.getServiceName());
        alert.put("labels", Map.of(
                "service", request.getServiceName(),
                "severity", severity,
                "traceId", request.getTraceId() == null ? "" : request.getTraceId(),
                "spanId", request.getSpanId() == null ? "" : request.getSpanId()));
        return alert;
    }

    private String suspectedCause(String healthStatus, int errorCount, int warnCount, int traceLogCount) {
        String normalizedHealth = healthStatus == null ? "" : healthStatus.toUpperCase(Locale.ROOT);
        if (normalizedHealth.contains("MEMORY")) {
            return "JVM memory pressure";
        }
        if (normalizedHealth.contains("CPU")) {
            return "CPU saturation";
        }
        if (normalizedHealth.contains("THREAD")) {
            return "thread pool saturation or blocking calls";
        }
        if (traceLogCount > 0 && errorCount > 0) {
            return "trace-level downstream or request-path failure";
        }
        if (errorCount > 0) {
            return "application error spike";
        }
        if (warnCount > 0) {
            return "warning-level degradation";
        }
        return "no strong local anomaly detected";
    }

    private String confidence(int errorCount,
                              int warnCount,
                              int traceLogCount,
                              String healthStatus,
                              Map<String, Object> recentChanges) {
        int score = 0;
        if (errorCount > 0) {
            score += 2;
        }
        if (warnCount > 0) {
            score += 1;
        }
        if (traceLogCount > 0) {
            score += 2;
        }
        if (healthStatus != null && !"HEALTHY".equalsIgnoreCase(healthStatus) && !"UNKNOWN".equalsIgnoreCase(healthStatus)) {
            score += 2;
        }
        if ("HIGH".equals(recentChanges.get("riskLevel"))) {
            score += 1;
        }
        if (score >= 5) {
            return "HIGH";
        }
        if (score >= 2) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private List<String> suggestedActions(String healthStatus, int errorCount, int traceLogCount, Map<String, Object> slo) {
        List<String> actions = new ArrayList<>();
        if (Boolean.TRUE.equals(slo.get("alertTriggered"))) {
            actions.add("treat as SLO burn: assign owner, freeze risky changes, and track burn-rate recovery");
        }
        if (healthStatus != null && healthStatus.toUpperCase(Locale.ROOT).contains("MEMORY")) {
            actions.add("check heap usage trend, recent object allocation changes, and GC pause distribution");
        }
        if (healthStatus != null && healthStatus.toUpperCase(Locale.ROOT).contains("CPU")) {
            actions.add("inspect hot endpoints, thread dumps, and recent traffic changes");
        }
        if (traceLogCount > 0) {
            actions.add("follow trace logs to identify the first failing service and downstream dependency");
        }
        if (errorCount > 0) {
            actions.add("group error logs by exception type and compare with recent release or config changes");
        }
        if (actions.isEmpty()) {
            actions.add("expand the time window or add traceId/release context to strengthen evidence");
        }
        return actions;
    }

    private List<String> playbookHints(Map<String, Object> slo,
                                       String healthStatus,
                                       int errorCount,
                                       int traceLogCount,
                                       Map<String, Object> recentChanges) {
        List<String> hints = new ArrayList<>();
        String severity = stringValue(slo.get("severity"));
        if ("CRITICAL".equals(severity) || "HIGH".equals(severity)) {
            hints.add("incident-commander: open incident channel, pin evidence bundle, and update status every burn window");
        }
        if ("HIGH".equals(recentChanges.get("riskLevel"))) {
            hints.add("release-playbook: compare recent release/config diff with first failing service before rollback");
        }
        if (healthStatus != null && healthStatus.toUpperCase(Locale.ROOT).contains("MEMORY")) {
            hints.add("jvm-memory-playbook: capture heap histogram, GC log, and allocation hot path before restart");
        }
        if (traceLogCount > 0) {
            hints.add("trace-playbook: follow first failing span and collect downstream owner confirmation");
        }
        if (errorCount > 0) {
            hints.add("log-playbook: aggregate exception fingerprints and link top signature to recent changes");
        }
        if (hints.isEmpty()) {
            hints.add("triage-playbook: widen window, add traceId/release context, then rebuild evidence bundle");
        }
        return hints.stream().limit(4).toList();
    }

    private Map<String, Object> buildServiceTopology(OpsRcaRequest request,
                                                     Map<String, Object> logs,
                                                     Map<String, Object> warnings,
                                                     Map<String, Object> trace) {
        List<String> services = new ArrayList<>();
        Object traceServices = trace.get("services");
        if (traceServices instanceof Iterable<?> iterable) {
            for (Object service : iterable) {
                addService(services, String.valueOf(service));
            }
        }
        addService(services, request.getServiceName());
        collectServicesFromLogContainer(services, trace.get("logs"));
        collectServicesFromLogContainer(services, logs.get("logs"));
        collectServicesFromLogContainer(services, logs.get("samples"));
        collectServicesFromLogContainer(services, warnings.get("logs"));

        if (services.size() == 1) {
            for (String fallback : List.of("gateway-service", "order-service", "inventory-service", "payment-service")) {
                if (services.size() >= 3) {
                    break;
                }
                addService(services, fallback);
            }
        }

        List<Map<String, Object>> edges = new ArrayList<>();
        for (int index = 0; index < services.size() - 1; index++) {
            edges.add(Map.of(
                    "from", services.get(index),
                    "to", services.get(index + 1),
                    "relation", index == 0 ? "entry-call" : "downstream-call"));
        }

        String firstFailing = firstFailingService(trace.get("logs"), logs.get("logs"), request.getServiceName());
        Map<String, Object> topology = new LinkedHashMap<>();
        topology.put("rootService", services.isEmpty() ? request.getServiceName() : services.get(0));
        topology.put("suspectService", firstFailing);
        topology.put("impactedServices", services);
        topology.put("dependencyEdges", edges);
        topology.put("source", intValue(trace.get("count")) > 0 ? "trace" : "logs+fallback");
        return topology;
    }

    private Map<String, Object> buildTraceSummary(Map<String, Object> trace,
                                                  Map<String, Object> topology,
                                                  OpsRcaRequest request) {
        List<Map<String, Object>> path = new ArrayList<>();
        Object logs = trace.get("logs");
        String firstFailingSpanId = "";
        if (logs instanceof Iterable<?> iterable) {
            int index = 0;
            for (Object item : iterable) {
                if (item instanceof Map<?, ?> log) {
                    String service = stringValue(logValue(log, "service", "projectName"));
                    String level = stringValue(log.get("level"));
                    String spanId = stringValue(logValue(log, "spanId", "span_id"));
                    String message = stringValue(log.get("message"));
                    if (StringUtils.hasText(service)) {
                        path.add(Map.of(
                                "order", index++,
                                "service", service,
                                "spanId", spanId,
                                "level", StringUtils.hasText(level) ? level : "INFO",
                                "summary", abbreviate(message, 120)));
                    }
                    if (!StringUtils.hasText(firstFailingSpanId)
                            && ("ERROR".equalsIgnoreCase(level) || message.toUpperCase(Locale.ROOT).contains("ERROR"))) {
                        firstFailingSpanId = spanId;
                    }
                }
            }
        }
        if (!StringUtils.hasText(firstFailingSpanId)) {
            firstFailingSpanId = StringUtils.hasText(request.getSpanId()) ? request.getSpanId() : firstSpanId(path);
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("traceId", trace.getOrDefault("traceId", request.getTraceId() == null ? "" : request.getTraceId()));
        summary.put("requestedSpanId", request.getSpanId() == null ? "" : request.getSpanId());
        summary.put("spanCount", path.size());
        summary.put("firstFailingService", topology.get("suspectService"));
        summary.put("firstFailingSpanId", firstFailingSpanId);
        summary.put("path", path);
        return summary;
    }

    private Map<String, Object> buildLogAggregation(Map<String, Object> logs,
                                                    Map<String, Object> warnings,
                                                    Map<String, Object> trace) {
        Map<String, Integer> byLevel = new LinkedHashMap<>();
        Map<String, Integer> byService = new LinkedHashMap<>();
        Map<String, Integer> bySpanId = new LinkedHashMap<>();
        Map<String, Integer> fingerprints = new LinkedHashMap<>();
        addLogAggregation(logs.get("logs"), byLevel, byService, bySpanId, fingerprints);
        addLogAggregation(logs.get("samples"), byLevel, byService, bySpanId, fingerprints);
        addLogAggregation(warnings.get("logs"), byLevel, byService, bySpanId, fingerprints);
        addLogAggregation(trace.get("logs"), byLevel, byService, bySpanId, fingerprints);

        Map<String, Object> aggregation = new LinkedHashMap<>();
        aggregation.put("totalLogs", byLevel.values().stream().mapToInt(Integer::intValue).sum());
        aggregation.put("byLevel", byLevel);
        aggregation.put("byService", byService);
        aggregation.put("bySpanId", bySpanId);
        aggregation.put("topFingerprints", topEntries(fingerprints, 5));
        aggregation.put("aggregationMode", "logs+trace-local");
        return aggregation;
    }

    private Map<String, Object> buildSignalCorrelation(OpsRcaRequest request,
                                                       Map<String, Object> logs,
                                                       Map<String, Object> warnings,
                                                       Map<String, Object> metrics,
                                                       Map<String, Object> trace,
                                                       Map<String, Object> latencyRange,
                                                       Map<String, Object> errorRateRange,
                                                       Map<String, Object> serviceTopology,
                                                       Map<String, Object> recentChanges,
                                                       Map<String, Object> logAggregation,
                                                       Map<String, Object> slo) {
        boolean hasLogs = intValue(logs.get("count")) > 0 || intValue(warnings.get("count")) > 0;
        boolean hasTrace = intValue(trace.get("count")) > 0 || StringUtils.hasText(request.getTraceId());
        boolean hasMetrics = metrics.containsKey("healthStatus");
        boolean hasRangeMetrics = hasSeries(latencyRange) || hasSeries(errorRateRange);
        boolean hasChanges = recentChanges.get("items") instanceof Iterable<?>;
        int covered = 0;
        covered += hasLogs ? 1 : 0;
        covered += hasTrace ? 1 : 0;
        covered += hasMetrics ? 1 : 0;
        covered += hasRangeMetrics ? 1 : 0;
        covered += hasChanges ? 1 : 0;

        List<String> linkedSignals = new ArrayList<>();
        if (hasLogs) {
            linkedSignals.add("logs");
        }
        if (hasTrace) {
            linkedSignals.add("trace");
        }
        if (hasMetrics) {
            linkedSignals.add("metrics");
        }
        if (hasRangeMetrics) {
            linkedSignals.add("promql-range");
        }
        if (hasChanges) {
            linkedSignals.add("recent-changes");
        }

        Map<String, Object> correlation = new LinkedHashMap<>();
        correlation.put("serviceLabels", Map.of(
                "service", request.getServiceName(),
                "traceId", request.getTraceId() == null ? "" : request.getTraceId(),
                "spanId", request.getSpanId() == null ? "" : request.getSpanId()));
        correlation.put("timeWindowMinutes", request.getWindowMinutes());
        correlation.put("coverageScore", round(covered / 5.0));
        correlation.put("linkedSignals", linkedSignals);
        correlation.put("logAggregation", logAggregation);
        correlation.put("metricStatus", metrics.getOrDefault("healthStatus", "UNKNOWN"));
        correlation.put("topologySource", serviceTopology.getOrDefault("source", "unknown"));
        correlation.put("changeRisk", recentChanges.getOrDefault("riskLevel", "UNKNOWN"));
        correlation.put("sloSeverity", slo.getOrDefault("severity", "UNKNOWN"));
        correlation.put("evidenceCompleteness", covered >= 4 ? "STRONG" : covered >= 2 ? "PARTIAL" : "WEAK");
        correlation.put("explanation", "correlates logs, metrics, traces, topology, SLO, and recent changes in one RCA evidence bundle");
        return correlation;
    }

    private Map<String, Object> buildRecentChanges(OpsRcaRequest request,
                                                   Instant incidentStart,
                                                   Instant incidentEnd,
                                                   Map<String, Object> topology) {
        Instant changeStart = incidentEnd.minus(Duration.ofMinutes(request.getChangeWindowMinutes()));
        List<Map<String, Object>> changes = new ArrayList<>();
        if (StringUtils.hasText(request.getReleaseVersion())) {
            changes.add(Map.of(
                    "type", "release",
                    "serviceName", request.getServiceName(),
                    "version", request.getReleaseVersion(),
                    "changedAt", incidentEnd.minus(Duration.ofMinutes(Math.min(20, request.getChangeWindowMinutes() / 2))).toString(),
                    "risk", "HIGH",
                    "summary", "request supplied release context overlaps the incident window"));
        }
        if (StringUtils.hasText(request.getConfigKey())) {
            changes.add(Map.of(
                    "type", "config",
                    "serviceName", request.getServiceName(),
                    "configKey", request.getConfigKey(),
                    "changedAt", incidentEnd.minus(Duration.ofMinutes(Math.min(10, request.getChangeWindowMinutes() / 3))).toString(),
                    "risk", "MEDIUM",
                    "summary", "request supplied config context should be checked before remediation"));
        }
        if (changes.isEmpty()) {
            changes.add(Map.of(
                    "type", "release",
                    "serviceName", topology.getOrDefault("suspectService", request.getServiceName()),
                    "version", "unknown",
                    "changedAt", "",
                    "risk", "LOW",
                    "summary", "no release/config hint supplied; verify deployment system manually"));
        }

        boolean highRisk = changes.stream().anyMatch(change -> "HIGH".equals(change.get("risk")));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("window", Map.of(
                "start", changeStart.toString(),
                "end", incidentEnd.toString(),
                "minutes", request.getChangeWindowMinutes()));
        result.put("incidentWindowStart", incidentStart.toString());
        result.put("items", changes);
        result.put("riskLevel", highRisk ? "HIGH" : "LOW");
        result.put("reviewHint", highRisk
                ? "prioritize diffing release/config changes against first failing service"
                : "no explicit change evidence; continue with logs/metrics/trace correlation");
        return result;
    }

    private List<Map<String, Object>> buildEvidenceTimeline(Map<String, Object> logs,
                                                            Map<String, Object> warnings,
                                                            Map<String, Object> trace,
                                                            Map<String, Object> recentChanges) {
        List<Map<String, Object>> timeline = new ArrayList<>();
        Object changes = recentChanges.get("items");
        if (changes instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item instanceof Map<?, ?> change) {
                    timeline.add(Map.of(
                            "time", stringValue(change.get("changedAt")),
                            "kind", "change",
                            "service", stringValue(change.get("serviceName")),
                            "summary", stringValue(change.get("summary"))));
                }
            }
        }
        addLogTimeline(timeline, trace.get("logs"), "trace");
        addLogTimeline(timeline, logs.get("logs"), "error_log");
        addLogTimeline(timeline, logs.get("samples"), "error_log");
        addLogTimeline(timeline, warnings.get("logs"), "warning_log");
        return timeline.stream().limit(12).toList();
    }

    private void addLogTimeline(List<Map<String, Object>> timeline, Object rawLogs, String kind) {
        if (!(rawLogs instanceof Iterable<?> iterable)) {
            return;
        }
        for (Object item : iterable) {
            if (timeline.size() >= 12) {
                return;
            }
            if (item instanceof Map<?, ?> log) {
                timeline.add(Map.of(
                        "time", stringValue(log.get("timestamp")),
                        "kind", kind,
                        "service", stringValue(logValue(log, "service", "projectName")),
                        "summary", abbreviate(stringValue(log.get("message")), 120)));
            }
        }
    }

    private void addLogAggregation(Object rawLogs,
                                   Map<String, Integer> byLevel,
                                   Map<String, Integer> byService,
                                   Map<String, Integer> bySpanId,
                                   Map<String, Integer> fingerprints) {
        if (!(rawLogs instanceof Iterable<?> iterable)) {
            return;
        }
        for (Object item : iterable) {
            if (item instanceof Map<?, ?> log) {
                increment(byLevel, stringOrDefault(log.get("level"), "UNKNOWN").toUpperCase(Locale.ROOT));
                increment(byService, stringOrDefault(logValue(log, "service", "projectName"), "unknown-service"));
                String spanId = stringValue(logValue(log, "spanId", "span_id"));
                if (StringUtils.hasText(spanId)) {
                    increment(bySpanId, spanId);
                }
                String fingerprint = fingerprint(log);
                if (StringUtils.hasText(fingerprint)) {
                    increment(fingerprints, fingerprint);
                }
            } else if (item != null) {
                increment(fingerprints, abbreviate(String.valueOf(item), 80));
            }
        }
    }

    private String fingerprint(Map<?, ?> log) {
        String sourceClass = stringValue(log.get("sourceClass"));
        String sourceMethod = stringValue(log.get("sourceMethod"));
        if (StringUtils.hasText(sourceClass)) {
            return sourceClass + (StringUtils.hasText(sourceMethod) ? "#" + sourceMethod : "");
        }
        String message = stringValue(log.get("message"));
        if (!StringUtils.hasText(message)) {
            return "";
        }
        return abbreviate(message.replaceAll("\\d+", "#"), 80);
    }

    private List<Map<String, Object>> topEntries(Map<String, Integer> counts, int limit) {
        return counts.entrySet().stream()
                .sorted((left, right) -> Integer.compare(right.getValue(), left.getValue()))
                .limit(limit)
                .map(entry -> Map.<String, Object>of("key", entry.getKey(), "count", entry.getValue()))
                .toList();
    }

    private boolean hasSeries(Map<String, Object> rangeResult) {
        Object series = rangeResult.get("series");
        return series instanceof Iterable<?> iterable && iterable.iterator().hasNext();
    }

    private void collectServicesFromLogContainer(List<String> services, Object rawLogs) {
        if (!(rawLogs instanceof Iterable<?> iterable)) {
            return;
        }
        for (Object item : iterable) {
            if (item instanceof Map<?, ?> log) {
                addService(services, stringValue(logValue(log, "service", "projectName")));
                collectServicesFromText(services, stringValue(log.get("message")));
            } else {
                collectServicesFromText(services, String.valueOf(item));
            }
        }
    }

    private void collectServicesFromText(List<String> services, String text) {
        if (!StringUtils.hasText(text)) {
            return;
        }
        java.util.regex.Matcher matcher = SERVICE_PATTERN.matcher(text);
        while (matcher.find()) {
            addService(services, matcher.group(1));
        }
    }

    private String firstFailingService(Object traceLogs, Object errorLogs, String fallback) {
        String fromTrace = firstErrorService(traceLogs);
        if (StringUtils.hasText(fromTrace)) {
            return fromTrace;
        }
        String fromErrors = firstErrorService(errorLogs);
        return StringUtils.hasText(fromErrors) ? fromErrors : fallback;
    }

    private String firstErrorService(Object rawLogs) {
        if (!(rawLogs instanceof Iterable<?> iterable)) {
            return "";
        }
        for (Object item : iterable) {
            if (item instanceof Map<?, ?> log) {
                String level = stringValue(log.get("level"));
                String message = stringValue(log.get("message"));
                if ("ERROR".equalsIgnoreCase(level) || message.toUpperCase(Locale.ROOT).contains("ERROR")) {
                    return stringValue(logValue(log, "service", "projectName"));
                }
            }
        }
        return "";
    }

    private void addService(List<String> services, String service) {
        if (StringUtils.hasText(service) && !services.contains(service)) {
            services.add(service);
        }
    }

    private String firstSpanId(List<Map<String, Object>> path) {
        for (Map<String, Object> item : path) {
            String spanId = stringValue(item.get("spanId"));
            if (StringUtils.hasText(spanId)) {
                return spanId;
            }
        }
        return "";
    }

    private void increment(Map<String, Integer> counts, String key) {
        if (StringUtils.hasText(key)) {
            counts.merge(key, 1, Integer::sum);
        }
    }

    private String stringOrDefault(Object value, String defaultValue) {
        String text = stringValue(value);
        return StringUtils.hasText(text) ? text : defaultValue;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Object logValue(Map<?, ?> log, String primaryKey, String fallbackKey) {
        Object primary = log.get(primaryKey);
        return primary == null ? log.get(fallbackKey) : primary;
    }

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private Map<String, Object> safeMap(SupplierWithException<Map<String, Object>> supplier) {
        try {
            Map<String, Object> value = supplier.get();
            return value == null ? Map.of() : value;
        } catch (Exception ex) {
            return Map.of("error", ex.getMessage());
        }
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private String firstMatch(String input, Pattern pattern, String defaultValue) {
        if (!StringUtils.hasText(input)) {
            return defaultValue;
        }
        java.util.regex.Matcher matcher = pattern.matcher(input);
        return matcher.find() ? matcher.group(1) : defaultValue;
    }

    @FunctionalInterface
    private interface SupplierWithException<T> {
        T get() throws Exception;
    }
}
