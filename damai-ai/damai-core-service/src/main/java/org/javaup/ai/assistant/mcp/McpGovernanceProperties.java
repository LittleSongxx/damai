package org.javaup.ai.assistant.mcp;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashSet;
import java.util.Set;

@Data
@ConfigurationProperties(prefix = "damai.ai.mcp.governance")
public class McpGovernanceProperties {

    private boolean enabled = true;

    private boolean requireAdmin = true;

    private boolean requireHighRiskConfirmation = true;

    private boolean exposeNl2Sql = false;

    private Set<String> allowlist = new LinkedHashSet<>(Set.of(
            "log.getServiceList",
            "log.searchLogsByKeyword",
            "log.getLogsByTraceId",
            "log.getLatestLogs",
            "log.getErrorLogs",
            "log.getWarnLogs",
            "log.getLogStatistics",
            "log.searchLogsByClass",
            "metrics.getMetricsServiceList",
            "metrics.getJvmMemory",
            "metrics.getCpuMetrics",
            "metrics.getServiceHealthOverview",
            "metrics.getAllServicesHealth"
    ));

    private Set<String> resourceAllowlist = new LinkedHashSet<>(Set.of(
            "observability://logs/services",
            "observability://metrics/services",
            "observability://traces/{traceId}",
            "assistant://runs/{runId}/graph"
    ));

    private Set<String> promptAllowlist = new LinkedHashSet<>(Set.of(
            "ops.rca",
            "ops.log-diagnosis",
            "ops.metrics-diagnosis"
    ));

    private Set<String> highRiskTools = new LinkedHashSet<>(Set.of(
            "nl2sql.nl2sqlQuery"
    ));

    private Set<String> highRiskResources = new LinkedHashSet<>(Set.of(
            "assistant://runs/{runId}/graph"
    ));

    private Set<String> highRiskPrompts = new LinkedHashSet<>(Set.of(
            "ops.rca"
    ));
}
