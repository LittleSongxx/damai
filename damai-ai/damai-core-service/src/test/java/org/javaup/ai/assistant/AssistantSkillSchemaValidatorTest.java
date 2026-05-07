package org.javaup.ai.assistant;

import org.javaup.ai.context.AiUserContext;
import org.javaup.ai.dto.AssistantRunCreateRequest;
import org.javaup.ai.entity.AiRun;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AssistantSkillSchemaValidatorTest {

    private final AssistantSkillSchemaValidator validator = new AssistantSkillSchemaValidator();

    @Test
    void shouldRejectInputMissingRequiredMessage() {
        AssistantSkillDescriptor descriptor = baseDescriptor().toBuilder()
                .inputSchemaJson("{\"required\":[\"message\"]}")
                .build();
        AssistantRunCreateRequest request = new AssistantRunCreateRequest();
        request.setMessage(" ");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                validator.validateInput(descriptor, AssistantSkillContext.of(run(), user(), request)));

        assertEquals("Skill business.program.search input 缺少必填字段: message", exception.getMessage());
    }

    @Test
    void shouldAllowInputWithRequiredMessage() {
        AssistantSkillDescriptor descriptor = baseDescriptor().toBuilder()
                .inputSchemaJson("{\"required\":[\"message\"]}")
                .build();
        AssistantRunCreateRequest request = new AssistantRunCreateRequest();
        request.setMessage("周末上海演唱会");

        assertDoesNotThrow(() -> validator.validateInput(descriptor, AssistantSkillContext.of(run(), user(), request)));
    }

    @Test
    void shouldRejectOutputMissingRequiredSummary() {
        AssistantSkillDescriptor descriptor = baseDescriptor().toBuilder()
                .outputSchemaJson("{\"required\":[\"message\",\"responseSummary\"]}")
                .build();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                validator.validateOutput(descriptor, AssistantSkillResult.builder()
                        .message("已找到 3 个节目")
                        .build()));

        assertEquals("Skill business.program.search output 缺少必填字段: responseSummary", exception.getMessage());
    }

    private AssistantSkillDescriptor baseDescriptor() {
        return AssistantSkillDescriptor.builder()
                .skillId("business.program.search")
                .name("节目搜索")
                .description("搜索节目")
                .version("1.0.0")
                .routeType(AssistantRouteType.BUSINESS)
                .category("business")
                .riskLevel(AssistantSkillRiskLevel.LOW)
                .requiresAdmin(false)
                .requiresApproval(false)
                .enabled(true)
                .executorType("java")
                .frontendSelectable(true)
                .modelSelectable(true)
                .primarySkill(false)
                .build();
    }

    private AiRun run() {
        AiRun run = new AiRun();
        run.setRunId("run_1");
        run.setConversationId("chat_1");
        run.setUserId(1L);
        return run;
    }

    private AiUserContext user() {
        return AiUserContext.builder().userId(1L).build();
    }
}
