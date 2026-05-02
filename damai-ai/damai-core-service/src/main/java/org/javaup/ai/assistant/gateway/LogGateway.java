package org.javaup.ai.assistant.gateway;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.easyes.core.conditions.select.LambdaEsQueryWrapper;
import org.javaup.ai.es.mapper.OpsLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LogGateway {

    private final OpsLogMapper logMapper;

    public Map<String, Object> getServiceList() {
        List<String> serviceList = getServiceListFromEs();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("services", serviceList);
        result.put("count", serviceList.size());
        return result;
    }

    public Map<String, Object> searchLogsByKeyword(String keyword, String serviceName, String level, Integer size) {
        int limit = (size != null && size > 0) ? Math.min(size, 100) : 20;
        LambdaEsQueryWrapper<LogDocument> wrapper = new LambdaEsQueryWrapper<>();
        wrapper.match(LogDocument::getMessage, keyword);
        if (StringUtils.hasText(serviceName)) {
            wrapper.match(LogDocument::getProjectName, serviceName);
        }
        if (StringUtils.hasText(level)) {
            wrapper.match(LogDocument::getLevel, level.toUpperCase());
        }
        wrapper.orderByDesc(LogDocument::getTimestamp);
        wrapper.limit(limit);
        List<LogDocument> logs = logMapper.selectList(wrapper);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", Map.of(
                "keyword", keyword,
                "serviceName", serviceName == null ? "" : serviceName,
                "level", level == null ? "" : level.toUpperCase()
        ));
        result.put("logs", formatLogs(logs));
        result.put("count", logs.size());
        return result;
    }

    public Map<String, Object> getLogsByTraceId(String traceId) {
        LambdaEsQueryWrapper<LogDocument> wrapper = new LambdaEsQueryWrapper<>();
        wrapper.match(LogDocument::getTraceId, traceId);
        wrapper.orderByAsc(LogDocument::getTimeMillis);
        wrapper.limit(200);
        List<LogDocument> logs = logMapper.selectList(wrapper);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceId", traceId);
        result.put("count", logs.size());
        result.put("services", logs.stream().map(LogDocument::getProjectName).distinct().toList());
        result.put("logs", formatLogs(logs));
        return result;
    }

    private List<Map<String, Object>> formatLogs(List<LogDocument> logs) {
        return logs.stream().map(log -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("timestamp", log.getTimestamp());
            item.put("service", log.getProjectName());
            item.put("level", log.getLevel());
            item.put("message", log.getMessage());
            item.put("traceId", log.getTraceId());
            item.put("sourceClass", log.getSourceClass());
            item.put("sourceMethod", log.getSourceMethod());
            item.put("sourceLine", log.getSourceLine());
            item.put("thread", log.getThread());
            return item;
        }).toList();
    }

    private List<String> getServiceListFromEs() {
        try {
            String dsl = "{" +
                    "\"size\": 0," +
                    "\"aggs\": {" +
                    "  \"service_names\": {" +
                    "    \"terms\": {" +
                    "      \"field\": \"projectName.keyword\"," +
                    "      \"size\": 100" +
                    "    }" +
                    "  }" +
                    "}" +
                    "}";
            String jsonResult = logMapper.executeDSL(dsl);
            JSONObject root = JSON.parseObject(jsonResult);
            JSONArray buckets = root.getJSONObject("aggregations")
                    .getJSONObject("service_names")
                    .getJSONArray("buckets");
            if (buckets == null) {
                return List.of();
            }
            List<String> serviceList = new ArrayList<>();
            for (int i = 0; i < buckets.size(); i++) {
                String value = buckets.getJSONObject(i).getString("key");
                if (StringUtils.hasText(value)) {
                    serviceList.add(value);
                }
            }
            Collections.sort(serviceList);
            return serviceList;
        } catch (Exception ex) {
            log.warn("failed to load log services", ex);
            return List.of();
        }
    }
}
