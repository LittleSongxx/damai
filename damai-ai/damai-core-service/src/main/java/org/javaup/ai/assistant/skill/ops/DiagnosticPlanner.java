package org.javaup.ai.assistant.skill.ops;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * AIOps 诊断推理链规划器 —— 遵循 LangGraph chain 模式与 CrewAI 层次化任务委派设计。
 *
 * <p>LangGraph 的 chain 模式: 将复杂诊断任务分解为有序步骤
 * (概览→下钻→关联→结论)，每个步骤的输出作为下一步的输入。
 *
 * <p>CrewAI 的层次化任务委派: 根据问题类型（全局健康/特定服务/链路追踪/
 * 错误排查）选择不同的诊断路径，类似 CrewAI 的 Sequential Process。
 *
 * <p>诊断路径:
 * <ol>
 *   <li>GLOBAL_HEALTH: 获取所有服务健康概览 → 标记异常服务 → 下钻异常服务</li>
 *   <li>SERVICE_SPECIFIC: 查询指定服务健康概览 → 检查错误日志 → 检查 GC/线程/CPU</li>
 *   <li>TRACE_SPECIFIC: 按 traceId 拉取完整链路日志 → 定位故障节点</li>
 *   <li>ERROR_INVESTIGATION: 获取错误日志统计 → 下钻高频错误服务 → 追踪典型 traceId</li>
 * </ol>
 */
@Slf4j
@Service
public class DiagnosticPlanner {

    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("[a-fA-F0-9]{16,64}");
    private static final Pattern SERVICE_NAME_PATTERN = Pattern.compile("[a-z]+-[a-z]+(?:-[a-z]+)*");

    /**
     * 根据用户问题规划诊断链。
     *
     * @param userQuery 用户的运维诊断问题
     * @return 诊断计划，包含有序步骤
     */
    public DiagnosticPlan plan(String userQuery) {
        String query = userQuery != null ? userQuery.toLowerCase().trim() : "";
        DiagnosticPath path = classify(query);
        return buildPlan(query, path);
    }

    /**
     * 识别诊断路径。
     */
    DiagnosticPath classify(String query) {
        if (query.contains("trace") || query.contains("traceid") || query.contains("链路")) {
            String traceId = extractTraceId(query);
            if (traceId != null) {
                return DiagnosticPath.TRACE_SPECIFIC;
            }
        }
        if (query.contains("错误") || query.contains("error") || query.contains("异常")
                || query.contains("exception") || query.contains("故障")) {
            if (extractServiceName(query) != null) {
                return DiagnosticPath.SERVICE_SPECIFIC;
            }
            return DiagnosticPath.ERROR_INVESTIGATION;
        }
        if (query.contains("健康") || query.contains("health") || query.contains("状态")
                || query.contains("概览") || query.contains("overview") || query.contains("整体")) {
            return DiagnosticPath.GLOBAL_HEALTH;
        }
        String serviceName = extractServiceName(query);
        if (serviceName != null) {
            return DiagnosticPath.SERVICE_SPECIFIC;
        }
        return DiagnosticPath.GLOBAL_HEALTH;
    }

    /**
     * 构建诊断链步骤。
     */
    private DiagnosticPlan buildPlan(String query, DiagnosticPath path) {
        var plan = new DiagnosticPlan(query);

        switch (path) {
            case GLOBAL_HEALTH -> {
                plan.addStep(new DiagnosticPlan.Step("概览", "getAllServicesHealth",
                        "获取所有微服务健康状态概览"));
                plan.addStep(new DiagnosticPlan.Step("下钻", "getErrorLogs",
                        "下钻检查标记为异常的服务最近错误日志", List.of(), true, "存在健康状态异常的服务"));
                plan.addStep(new DiagnosticPlan.Step("关联", "getLogStatistics",
                        "获取全局日志统计，关联分析错误分布"));
            }
            case SERVICE_SPECIFIC -> {
                String serviceName = extractServiceName(query);
                plan.addStep(new DiagnosticPlan.Step("概览", "getServiceHealthOverview",
                        "获取 " + (serviceName != null ? serviceName : "目标服务") + " 健康概览",
                        serviceName != null ? List.of(serviceName) : List.of()));
                plan.addStep(new DiagnosticPlan.Step("下钻", "getErrorLogs",
                        "获取该服务最近错误日志",
                        serviceName != null ? List.of(serviceName) : List.of()));
                plan.addStep(new DiagnosticPlan.Step("指标", "getJvmMemory",
                        "检查 JVM 堆内存是否正常", serviceName != null ? List.of(serviceName) : List.of()));
                plan.addStep(new DiagnosticPlan.Step("指标", "getGcMetrics",
                        "检查 GC 是否频繁/耗时过长", serviceName != null ? List.of(serviceName) : List.of()));
            }
            case TRACE_SPECIFIC -> {
                String traceId = extractTraceId(query);
                plan.addStep(new DiagnosticPlan.Step("追踪", "getLogsByTraceId",
                        "按 traceId=" + (traceId != null ? traceId : "?") + " 拉取完整调用链路"));
                plan.addStep(new DiagnosticPlan.Step("关联", "getServiceHealthOverview",
                        "关联检查链路中涉及服务的健康状态", List.of(), true, "链路中包含多个服务"));
            }
            case ERROR_INVESTIGATION -> {
                plan.addStep(new DiagnosticPlan.Step("概览", "getLogStatistics",
                        "获取全局错误日志统计分布"));
                plan.addStep(new DiagnosticPlan.Step("下钻", "getErrorLogs",
                        "获取高频错误服务的详细错误日志", List.of(), true, "存在高频错误服务"));
                plan.addStep(new DiagnosticPlan.Step("追踪", "getLogsByTraceId",
                        "选择典型 traceId 进行链路追踪", List.of(), true, "错误日志中存在有效 traceId"));
            }
        }

        plan.addStep(new DiagnosticPlan.Step("结论", "SUMMARIZE",
                "综合分析各步骤结果，输出诊断结论与建议"));
        return plan;
    }

    /**
     * 从查询中提取 traceId。
     */
    String extractTraceId(String query) {
        if (query == null) {
            return null;
        }
        var matcher = TRACE_ID_PATTERN.matcher(query);
        if (matcher.find()) {
            return matcher.group();
        }
        return null;
    }

    /**
     * 从查询中提取服务名。
     */
    String extractServiceName(String query) {
        if (query == null) {
            return null;
        }
        var matcher = SERVICE_NAME_PATTERN.matcher(query);
        if (matcher.find()) {
            String candidate = matcher.group();
            if (candidate.endsWith("-service") || candidate.endsWith("-server")
                    || candidate.startsWith("damai-") || candidate.contains("gateway")
                    || candidate.contains("order") || candidate.contains("user")) {
                return candidate;
            }
        }
        return null;
    }

    enum DiagnosticPath {
        GLOBAL_HEALTH,
        SERVICE_SPECIFIC,
        TRACE_SPECIFIC,
        ERROR_INVESTIGATION
    }
}
