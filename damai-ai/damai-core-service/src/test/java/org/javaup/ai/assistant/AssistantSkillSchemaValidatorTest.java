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

        assertEquals(true, exception.getMessage().contains("JSON Schema 校验失败"));
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

        assertEquals(true, exception.getMessage().contains("JSON Schema 校验失败"));
    }

    @Test
    void shouldRejectInputThatViolatesJsonSchemaTypeAndLength() {
        AssistantSkillDescriptor descriptor = baseDescriptor().toBuilder()
                .inputSchemaJson("""
                        {
                          "type": "object",
                          "required": ["message"],
                          "properties": {
                            "message": {"type": "string", "minLength": 3},
                            "clientContext": {
                              "type": "object",
                              "properties": {
                                "routeHint": {"type": "string", "enum": ["business", "knowledge"]}
                              }
                            }
                          }
                        }
                        """)
                .build();
        AssistantRunCreateRequest request = new AssistantRunCreateRequest();
        request.setMessage("hi");
        request.setClientContext(java.util.Map.of("routeHint", "ops"));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                validator.validateInput(descriptor, AssistantSkillContext.of(run(), user(), request)));

        assertEquals(true, exception.getMessage().contains("JSON Schema 校验失败"));
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
