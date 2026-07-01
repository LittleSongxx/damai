package org.javaup.ai.assistant.mcp.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.assistant.gateway.LogGateway;
import org.javaup.ai.assistant.mcp.McpToolGovernanceService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class LogMcpTools {

    private final LogGateway logGateway;
    private final McpToolGovernanceService governanceService;

    @Tool(description = "获取大麦系统中所有可用的微服务列表")
    public Map<String, Object> getServiceList() {
        return governanceService.execute("log.getServiceList", Map.of(), logGateway::getServiceList);
    }

    @Tool(description = "根据关键词搜索日志内容，支持模糊匹配日志消息")
    public Map<String, Object> searchLogsByKeyword(
            @ToolParam(description = "搜索关键词，用于匹配日志消息内容") String keyword,
            @ToolParam(description = "服务名称，可选。如：gateway-service、order-service等", required = false) String serviceName,
            @ToolParam(description = "日志级别，可选。如：INFO、WARN、ERROR、DEBUG", required = false) String level,
            @ToolParam(description = "返回的日志条数，默认20条", required = false) Integer size) {
        return governanceService.execute("log.searchLogsByKeyword",
                Map.of("keyword", value(keyword), "serviceName", value(serviceName), "level", value(level), "size", size == null ? 0 : size),
                () -> logGateway.searchLogsByKeyword(keyword, serviceName, level, size));
    }

    @Tool(description = "通过traceId查询完整的调用链路日志，串联所有微服务的日志记录，用于问题排查和链路追踪")
    public Map<String, Object> getLogsByTraceId(
            @ToolParam(description = "链路追踪ID（traceId）") String traceId) {
        return governanceService.execute("log.getLogsByTraceId",
                Map.of("traceId", value(traceId)),
                () -> logGateway.getLogsByTraceId(traceId));
    }

    @Tool(description = "查询指定微服务的最新日志记录")
    public Map<String, Object> getLatestLogs(
            @ToolParam(description = "服务名称，如：gateway-service、order-service、user-service等") String serviceName,
            @ToolParam(description = "日志级别，可选。如：INFO、WARN、ERROR、DEBUG", required = false) String level,
            @ToolParam(description = "返回的日志条数，默认20条", required = false) Integer size) {
        return governanceService.execute("log.getLatestLogs",
                Map.of("serviceName", value(serviceName), "level", value(level), "size", size == null ? 0 : size),
                () -> logGateway.searchLogsByKeyword(null, serviceName, level, size));
    }

    @Tool(description = "查询系统中的错误日志（ERROR级别）")
    public Map<String, Object> getErrorLogs(
            @ToolParam(description = "服务名称，可选。不填则查询所有服务的错误日志", required = false) String serviceName,
            @ToolParam(description = "返回的日志条数，默认30条", required = false) Integer size) {
        return governanceService.execute("log.getErrorLogs",
                Map.of("serviceName", value(serviceName), "size", size == null ? 0 : size),
                () -> logGateway.searchLogsByKeyword(null, serviceName, "ERROR", size));
    }

    @Tool(description = "查询系统中的警告日志（WARN级别）")
    public Map<String, Object> getWarnLogs(
            @ToolParam(description = "服务名称，可选。不填则查询所有服务的警告日志", required = false) String serviceName,
            @ToolParam(description = "返回的日志条数，默认30条", required = false) Integer size) {
        return governanceService.execute("log.getWarnLogs",
                Map.of("serviceName", value(serviceName), "size", size == null ? 0 : size),
                () -> logGateway.searchLogsByKeyword(null, serviceName, "WARN", size));
    }

    @Tool(description = "获取可用的微服务列表及日志概览信息")
    public Map<String, Object> getLogStatistics(
            @ToolParam(description = "服务名称，可选。不填则统计所有服务", required = false) String serviceName) {
        return governanceService.execute("log.getLogStatistics",
                Map.of("serviceName", value(serviceName)),
                () -> {
        Map<String, Object> result = logGateway.getServiceList();
        @SuppressWarnings("unchecked")
        List<String> services = (List<String>) result.get("services");
        if (serviceName != null && !serviceName.isEmpty()) {
            services = List.of(serviceName);
        }
        Map<String, Map<String, Object>> stats = new LinkedHashMap<>();
        for (String svc : services) {
            Map<String, Object> svcInfo = new LinkedHashMap<>();
            Map<String, Object> errResult = logGateway.searchLogsByKeyword(null, svc, "ERROR", 1);
            Map<String, Object> warnResult = logGateway.searchLogsByKeyword(null, svc, "WARN", 1);
            svcInfo.put("errorCount", errResult.get("count"));
            svcInfo.put("warnCount", warnResult.get("count"));
            stats.put(svc, svcInfo);
        }
        return Map.of("scope", serviceName != null ? serviceName : "all", "services", stats);
                });
    }

    @Tool(description = "根据类名或方法名搜索日志，用于定位特定代码位置的日志")
    public Map<String, Object> searchLogsByClass(
            @ToolParam(description = "类名，支持模糊匹配，如：UserController、OrderService") String className,
            @ToolParam(description = "方法名，可选", required = false) String methodName,
            @ToolParam(description = "返回的日志条数，默认20条", required = false) Integer size) {
        return governanceService.execute("log.searchLogsByClass",
                Map.of("className", value(className), "methodName", value(methodName), "size", size == null ? 0 : size),
                () -> logGateway.searchLogsByClass(className, methodName, size));
    }

    private String value(String raw) {
        return raw == null ? "" : raw;
    }
}
