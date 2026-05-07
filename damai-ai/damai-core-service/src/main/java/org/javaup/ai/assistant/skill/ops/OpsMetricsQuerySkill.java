package org.javaup.ai.assistant.skill.ops;

import org.javaup.ai.assistant.AssistantRouteType;
import org.javaup.ai.assistant.AssistantSkill;
import org.javaup.ai.assistant.AssistantSkillContext;
import org.javaup.ai.assistant.AssistantSkillDescriptor;
import org.javaup.ai.assistant.AssistantSkillResult;
import org.javaup.ai.assistant.AssistantSkillRiskLevel;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class OpsMetricsQuerySkill implements AssistantSkill {

    private final OpsSkill delegate;

    public OpsMetricsQuerySkill(OpsSkill delegate) {
        this.delegate = delegate;
    }

    @Override
    public AssistantRouteType routeType() {
        return AssistantRouteType.OPS;
    }

    @Override
    public AssistantSkillDescriptor descriptor() {
        return AssistantSkillDescriptor.builder()
                .skillId("ops.metrics.query")
                .name("运维指标查询")
                .description("查询服务健康、CPU、JVM、GC、线程和监控指标概览。")
                .version("1.0.0")
                .goal("查询 Prometheus 指标并基于真实指标给出服务健康分析。")
                .instructions("只能调用指标工具，不得把日志或 SQL 结果伪装成监控数据。")
                .routeType(AssistantRouteType.OPS)
                .category("ops")
                .triggerKeywords(List.of("指标", "监控", "cpu", "jvm", "gc", "线程", "健康"))
                .toolAllowlist(List.of("metricsGateway"))
                .examples(List.of("看一下 damai-ai 的 JVM 健康情况"))
                .evalCases(List.of("指标查询不能调用下单或 NL2SQL 工具"))
                .inputSchemaJson("""
                        {"type":"object","required":["message"],"properties":{"message":{"type":"string"}}}
                        """)
                .outputSchemaJson("""
                        {"type":"object","required":["message"],"properties":{"message":{"type":"string"}}}
                        """)
                .riskLevel(AssistantSkillRiskLevel.HIGH)
                .requiresAdmin(true)
                .requiresApproval(false)
                .enabled(true)
                .executorType("java")
                .frontendSelectable(true)
                .modelSelectable(true)
                .primarySkill(false)
                .build();
    }

    @Override
    public AssistantSkillResult execute(AssistantSkillContext context) {
        return delegate.execute(context);
    }
}
