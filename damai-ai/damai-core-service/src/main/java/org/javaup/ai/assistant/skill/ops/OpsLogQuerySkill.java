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
public class OpsLogQuerySkill implements AssistantSkill {

    private final OpsSkill delegate;

    public OpsLogQuerySkill(OpsSkill delegate) {
        this.delegate = delegate;
    }

    @Override
    public AssistantRouteType routeType() {
        return AssistantRouteType.OPS;
    }

    @Override
    public AssistantSkillDescriptor descriptor() {
        return AssistantSkillDescriptor.builder()
                .skillId("ops.log.query")
                .name("运维日志查询")
                .description("查询错误日志、异常关键词和服务日志线索。")
                .version("1.0.0")
                .goal("查询服务日志、错误日志和 trace 相关线索。")
                .instructions("只能基于日志证据分析，不得编造未出现的服务、异常或 trace。")
                .routeType(AssistantRouteType.OPS)
                .category("ops")
                .triggerKeywords(List.of("日志", "异常", "错误", "error", "exception", "失败"))
                .toolAllowlist(List.of("logGateway", "traceGateway"))
                .examples(List.of("查询 order-service 最近的 ERROR 日志"))
                .evalCases(List.of("日志查询不能调用 NL2SQL"))
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
