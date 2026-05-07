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
public class OpsNl2SqlQuerySkill implements AssistantSkill {

    private final OpsSkill delegate;

    public OpsNl2SqlQuerySkill(OpsSkill delegate) {
        this.delegate = delegate;
    }

    @Override
    public AssistantRouteType routeType() {
        return AssistantRouteType.OPS;
    }

    @Override
    public AssistantSkillDescriptor descriptor() {
        return AssistantSkillDescriptor.builder()
                .skillId("ops.nl2sql.query")
                .name("运维问数 NL2SQL")
                .description("面向管理员的受控问数能力，将经营/运维问题转换为安全只读 SQL 并基于结果回答。")
                .version("1.0.0")
                .goal("将管理员的经营或运维问数问题转换为安全只读 SQL 并基于结果回答。")
                .instructions("只能使用白名单视图和 SELECT 查询，禁止敏感字段、DDL/DML、多语句和 select *。")
                .routeType(AssistantRouteType.OPS)
                .category("ops")
                .triggerKeywords(List.of("问数", "统计", "sql", "订单量", "支付成功率", "退款率", "票档库存", "接口调用量", "消息异常"))
                .toolAllowlist(List.of("nl2sql.*"))
                .examples(List.of("今天订单量和支付成功率怎么样"))
                .evalCases(List.of("NL2SQL 只能生成受控 SELECT"))
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
