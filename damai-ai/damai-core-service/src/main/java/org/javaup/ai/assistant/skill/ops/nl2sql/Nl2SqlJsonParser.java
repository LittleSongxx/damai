package org.javaup.ai.assistant.skill.ops.nl2sql;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Component
public class Nl2SqlJsonParser {

    public Nl2SqlGenerationResult parseGenerationResult(String raw) {
        String json = extractJson(raw);
        JSONObject object = JSON.parseObject(json);
        Nl2SqlGenerationResult result = new Nl2SqlGenerationResult();
        if (object.containsKey("needSql")) {
            result.setNeedSql(Boolean.TRUE.equals(object.getBoolean("needSql")));
        }
        result.setSql(nullToEmpty(object.getString("sql")));
        result.setTables(stringList(object.getJSONArray("tables")));
        result.setExplanation(nullToEmpty(object.getString("explanation")));
        result.setChartType(StringUtils.hasText(object.getString("chartType")) ? object.getString("chartType") : "table");
        result.setConfidence(object.getDouble("confidence") == null ? 0.0D : object.getDouble("confidence"));
        result.setAssumptions(stringList(object.getJSONArray("assumptions")));
        return result;
    }

    private String extractJson(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new IllegalArgumentException("LLM response is empty");
        }
        String value = raw.trim();
        if (value.startsWith("```")) {
            value = value.replaceFirst("^```[a-zA-Z]*\\s*", "");
            value = value.replaceFirst("\\s*```$", "");
        }
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("LLM response does not contain a JSON object");
        }
        return value.substring(start, end + 1);
    }

    private List<String> stringList(JSONArray array) {
        List<String> values = new ArrayList<>();
        if (array == null) {
            return values;
        }
        for (Object item : array) {
            if (item != null && StringUtils.hasText(String.valueOf(item))) {
                values.add(String.valueOf(item));
            }
        }
        return values;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
