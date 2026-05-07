package org.javaup.ai.assistant;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

@Component
public class AssistantSkillSchemaValidator {

    public void validateInput(AssistantSkillDescriptor descriptor, AssistantSkillContext context) {
        if (descriptor == null || !StringUtils.hasText(descriptor.getInputSchemaJson())) {
            return;
        }
        validateObject(descriptor.getSkillId(), "input", descriptor.getInputSchemaJson(), Map.of(
                "message", context.getMessage() == null ? "" : context.getMessage(),
                "clientContext", context.getClientContext() == null ? Map.of() : context.getClientContext()
        ));
    }

    public void validateOutput(AssistantSkillDescriptor descriptor, AssistantSkillResult result) {
        if (descriptor == null || !StringUtils.hasText(descriptor.getOutputSchemaJson())) {
            return;
        }
        validateObject(descriptor.getSkillId(), "output", descriptor.getOutputSchemaJson(), Map.of(
                "message", result == null || result.getMessage() == null ? "" : result.getMessage(),
                "responseSummary", result == null || result.getResponseSummary() == null ? "" : result.getResponseSummary(),
                "hasPendingAction", result != null && result.getPendingAction() != null
        ));
    }

    private void validateObject(String skillId, String phase, String schemaJson, Map<String, Object> payload) {
        JSONObject schema = JSON.parseObject(schemaJson);
        JSONArray required = schema.getJSONArray("required");
        if (required == null) {
            return;
        }
        for (Object item : required) {
            String field = String.valueOf(item);
            Object value = payload.get(field);
            if (value == null || (value instanceof String text && !StringUtils.hasText(text))) {
                throw new IllegalArgumentException("Skill " + skillId + " " + phase + " 缺少必填字段: " + field);
            }
        }
    }
}
