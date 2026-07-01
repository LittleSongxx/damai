package org.javaup.ai.assistant.mcp.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.assistant.gateway.MetricsGateway;
import org.javaup.ai.assistant.mcp.McpToolGovernanceService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MetricsMcpTools {

    private final MetricsGateway metricsGateway;
    private final McpToolGovernanceService governanceService;

    @Tool(description = "获取大麦系统中所有被 Prometheus 监控的微服务列表")
    public Map<String, Object> getMetricsServiceList() {
        return governanceService.execute("metrics.getMetricsServiceList", Map.of(), metricsGateway::getServiceList);
    }

    @Tool(description = "查询指定微服务的 JVM 堆内存使用情况，包括已用内存、最大内存、使用率等")
    public Map<String, Object> getJvmMemory(
            @ToolParam(description = "服务名称，如：user-service、order-service") String serviceName) {
        return governanceService.execute("metrics.getJvmMemory",
                Map.of("serviceName", value(serviceName)),
                () -> metricsGateway.getJvmMemory(serviceName));
    }

    @Tool(description = "查询指定微服务的 CPU 使用情况，包括进程 CPU 使用率和系统 CPU 使用率")
    public Map<String, Object> getCpuMetrics(
            @ToolParam(description = "服务名称，如：user-service") String serviceName) {
        return governanceService.execute("metrics.getCpuMetrics",
                Map.of("serviceName", value(serviceName)),
                () -> metricsGateway.getCpuMetrics(serviceName));
    }

    @Tool(description = "查询指定微服务的健康概览，包括 JVM内存、CPU、线程、GC 等关键指标的综合展示")
    public Map<String, Object> getServiceHealthOverview(
            @ToolParam(description = "服务名称，如：user-service") String serviceName) {
        return governanceService.execute("metrics.getServiceHealthOverview",
                Map.of("serviceName", value(serviceName)),
                () -> metricsGateway.getServiceHealthOverview(serviceName));
    }

    @Tool(description = "查询所有微服务的健康状态概览，快速了解系统整体运行情况")
    public List<Map<String, Object>> getAllServicesHealth() {
        return governanceService.execute("metrics.getAllServicesHealth", Map.of(), () -> {
        Map<String, Object> listResult = metricsGateway.getServiceList();
        @SuppressWarnings("unchecked")
        List<String> services = (List<String>) listResult.get("services");
        if (services == null || services.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> healthList = new ArrayList<>();
        for (String svc : services) {
            Map<String, Object> overview = metricsGateway.getServiceHealthOverview(svc);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("service", svc);
            item.put("jvmMemory", overview.get("jvmMemory"));
            item.put("cpuUsage", overview.get("cpuUsage"));
            item.put("liveThreads", overview.get("liveThreads"));
            item.put("gc", overview.get("gc"));
            item.put("healthStatus", overview.get("healthStatus"));
            healthList.add(item);
        }
        return healthList;
        });
    }

    private String value(String raw) {
        return raw == null ? "" : raw;
    }
}
