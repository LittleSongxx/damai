package org.javaup.ai.assistant;

import org.javaup.ai.entity.AiSkill;
import org.javaup.ai.mapper.AiSkillChangeLogMapper;
import org.javaup.ai.mapper.AiSkillEvalCaseMapper;
import org.javaup.ai.mapper.AiSkillEvalRunMapper;
import org.javaup.ai.mapper.AiSkillMapper;
import org.javaup.ai.mapper.AiSkillResourceMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssistantSkillDefinitionServiceTest {

    @Test
    void shouldOverlayDatabaseDescriptorOntoJavaDescriptor() {
        AiSkillMapper skillMapper = mock(AiSkillMapper.class);
        AiSkill dbSkill = new AiSkill();
        dbSkill.setSkillId("ops.nl2sql.query");
        dbSkill.setName("受控问数");
        dbSkill.setDescription("DB覆盖描述");
        dbSkill.setGoal("只回答白名单 SQL 问数");
        dbSkill.setInstructions("只能生成 SELECT，并返回查询说明。");
        dbSkill.setTriggerKeywordsJson("[\"订单量\",\"支付成功率\"]");
        dbSkill.setToolAllowlistJson("[\"nl2sql.generate\",\"nl2sql.execute\"]");
        dbSkill.setExamplesJson("[\"查询昨天支付成功订单量\"]");
        dbSkill.setEvalCasesJson("[\"禁止执行 UPDATE 语句\"]");
        dbSkill.setRiskLevel("HIGH");
        dbSkill.setRequiresAdmin(1);
        dbSkill.setEnabled(0);
        when(skillMapper.selectOne(any())).thenReturn(dbSkill);
        AssistantSkillDefinitionService service = new AssistantSkillDefinitionService(
                skillMapper,
                mock(AiSkillResourceMapper.class),
                mock(AiSkillEvalCaseMapper.class),
                mock(AiSkillEvalRunMapper.class),
                mock(AiSkillChangeLogMapper.class)
        );

        AssistantSkillDescriptor merged = service.mergeDescriptor(AssistantSkillDescriptor.builder()
                .skillId("ops.nl2sql.query")
                .name("旧名称")
                .description("旧描述")
                .version("1.0.0")
                .routeType(AssistantRouteType.OPS)
                .category("ops")
                .triggerKeywords(List.of("问数"))
                .riskLevel(AssistantSkillRiskLevel.LOW)
                .requiresAdmin(false)
                .requiresApproval(false)
                .enabled(true)
                .executorType("java")
                .frontendSelectable(true)
                .modelSelectable(true)
                .primarySkill(false)
                .build());

        assertEquals("受控问数", merged.getName());
        assertEquals("DB覆盖描述", merged.getDescription());
        assertEquals("只回答白名单 SQL 问数", merged.getGoal());
        assertEquals("只能生成 SELECT，并返回查询说明。", merged.getInstructions());
        assertEquals(List.of("订单量", "支付成功率"), merged.getTriggerKeywords());
        assertEquals(List.of("nl2sql.generate", "nl2sql.execute"), merged.getToolAllowlist());
        assertEquals(List.of("查询昨天支付成功订单量"), merged.getExamples());
        assertEquals(List.of("禁止执行 UPDATE 语句"), merged.getEvalCases());
        assertEquals(AssistantSkillRiskLevel.HIGH, merged.getRiskLevel());
        assertFalse(merged.enabled());
    }
}
