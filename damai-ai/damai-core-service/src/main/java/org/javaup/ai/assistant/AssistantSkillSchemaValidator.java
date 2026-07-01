package org.javaup.ai.assistant;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class AssistantSkillSchemaValidator {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JsonSchemaFactory schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);

    public void validateInput(AssistantSkillDescriptor descriptor, AssistantSkillContext context) {
        if (descriptor == null || !StringUtils.hasText(descriptor.getInputSchemaJson())) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", context.getMessage() == null ? "" : context.getMessage());
        payload.put("clientContext", context.getClientContext() == null ? Map.of() : context.getClientContext());
        validateObject(descriptor.getSkillId(), "input", descriptor.getInputSchemaJson(), payload);
    }

    public void validateOutput(AssistantSkillDescriptor descriptor, AssistantSkillResult result) {
        if (descriptor == null || !StringUtils.hasText(descriptor.getOutputSchemaJson())) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("message", result == null || result.getMessage() == null ? "" : result.getMessage());
        payload.put("responseSummary", result == null || result.getResponseSummary() == null ? "" : result.getResponseSummary());
        payload.put("hasPendingAction", result != null && result.getPendingAction() != null);
        validateObject(descriptor.getSkillId(), "output", descriptor.getOutputSchemaJson(), payload);
    }

    private void validateObject(String skillId, String phase, String schemaJson, Map<String, Object> payload) {
        try {
            String normalizedSchema = normalizeObjectSchema(schemaJson);
            validateRequiredFields(skillId, phase, normalizedSchema, payload);
            JsonSchema schema = schemaFactory.getSchema(normalizedSchema);
            JsonNode node = objectMapper.valueToTree(payload);
            Set<ValidationMessage> errors = schema.validate(node);
            if (!errors.isEmpty()) {
                List<String> messages = errors.stream()
                        .map(ValidationMessage::getMessage)
                        .sorted()
                        .collect(Collectors.toList());
                throw new IllegalArgumentException("Skill " + skillId + " " + phase + " JSON Schema 校验失败: "
                        + String.join("; ", messages));
            }
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Skill " + skillId + " " + phase + " JSON Schema 解析失败: "
                    + ex.getMessage(), ex);
        }
    }

    private String normalizeObjectSchema(String schemaJson) {
        JSONObject schema = JSON.parseObject(schemaJson);
        if (!schema.containsKey("type")) {
            schema.put("type", "object");
        }
        return schema.toJSONString();
    }

    private void validateRequiredFields(String skillId, String phase, String schemaJson, Map<String, Object> payload) {
        JSONObject schema = JSON.parseObject(schemaJson);
        JSONArray required = schema.getJSONArray("required");
        if (required == null) {
            return;
        }
        for (Object item : required) {
            String field = String.valueOf(item);
            Object value = payload.get(field);
            if (value == null || (value instanceof String text && !StringUtils.hasText(text))) {
                throw new IllegalArgumentException("Skill " + skillId + " " + phase + " JSON Schema 校验失败: "
                        + field + " is required and must not be blank");
            }
        }
    }
}
