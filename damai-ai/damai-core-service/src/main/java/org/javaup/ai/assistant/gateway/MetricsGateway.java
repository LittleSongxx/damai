package org.javaup.ai.assistant.gateway;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.PostConstruct;
import org.javaup.ai.utils.CommonUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class MetricsGateway {

    @Value("${damai.ai.prometheus.url:http://127.0.0.1:9090}")
    private String prometheusUrl;

    private HttpClient httpClient;

    @PostConstruct
    public void init() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public Map<String, Object> getServiceList() {
        JSONObject json = queryPath("/api/v1/label/application/values");
        JSONArray data = json.getJSONArray("data");
        List<String> services = data == null ? List.of() : data.stream().map(Object::toString).sorted().toList();
        return Map.of("services", services, "count", services.size());
    }

    public Map<String, Object> getServiceHealthOverview(String serviceName) {
        Double heapUsed = querySingleMetric(String.format("sum(jvm_memory_used_bytes{application=\"%s\",area=\"heap\"})", serviceName));
        Double heapMax = querySingleMetric(String.format("sum(jvm_memory_max_bytes{application=\"%s\",area=\"heap\"})", serviceName));
        Double processCpu = querySingleMetric(String.format("process_cpu_usage{application=\"%s\"}", serviceName));
        Double liveThreads = querySingleMetric(String.format("jvm_threads_live_threads{application=\"%s\"}", serviceName));
        Double gcCount = querySingleMetric(String.format("sum(jvm_gc_pause_seconds_count{application=\"%s\"})", serviceName));
        Double gcTime = querySingleMetric(String.format("sum(jvm_gc_pause_seconds_sum{application=\"%s\"})", serviceName));

        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("used", heapUsed == null ? "N/A" : CommonUtils.formatBytes(heapUsed));
        memory.put("max", heapMax == null || heapMax <= 0 ? "UNBOUNDED" : CommonUtils.formatBytes(heapMax));
        memory.put("usageRate", heapUsed != null && heapMax != null && heapMax > 0
                ? String.format("%.2f%%", heapUsed / heapMax * 100)
                : "N/A");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("serviceName", serviceName);
        result.put("jvmMemory", memory);
        result.put("cpuUsage", processCpu == null ? "N/A" : String.format("%.2f%%", processCpu * 100));
        result.put("liveThreads", liveThreads == null ? "N/A" : liveThreads.longValue());
        result.put("gc", Map.of(
                "count", gcCount == null ? 0 : gcCount.longValue(),
                "time", gcTime == null ? "N/A" : String.format("%.3f s", gcTime)
        ));
        result.put("healthStatus", evaluateHealth(heapUsed, heapMax, processCpu, liveThreads));
        return result;
    }

    public Map<String, Object> getJvmMemory(String serviceName) {
        Map<String, Double> used = queryMetricByLabel(String.format("jvm_memory_used_bytes{application=\"%s\",area=\"heap\"}", serviceName), "id");
        Map<String, Double> max = queryMetricByLabel(String.format("jvm_memory_max_bytes{application=\"%s\",area=\"heap\"}", serviceName), "id");
        Map<String, Double> committed = queryMetricByLabel(String.format("jvm_memory_committed_bytes{application=\"%s\",area=\"heap\"}", serviceName), "id");
        List<Map<String, Object>> pools = new ArrayList<>();
        for (String pool : used.keySet()) {
            pools.add(Map.of(
                    "pool", pool,
                    "used", CommonUtils.formatBytes(used.get(pool)),
                    "committed", CommonUtils.formatBytes(committed.getOrDefault(pool, 0D)),
                    "max", max.getOrDefault(pool, -1D) > 0 ? CommonUtils.formatBytes(max.get(pool)) : "UNBOUNDED"
            ));
        }
        return Map.of("serviceName", serviceName, "pools", pools);
    }

    public Map<String, Object> getCpuMetrics(String serviceName) {
        Double processCpu = querySingleMetric(String.format("process_cpu_usage{application=\"%s\"}", serviceName));
        Double systemCpu = querySingleMetric(String.format("system_cpu_usage{application=\"%s\"}", serviceName));
        Double cpuCount = querySingleMetric(String.format("system_cpu_count{application=\"%s\"}", serviceName));
        return Map.of(
                "serviceName", serviceName,
                "processCpuUsage", processCpu == null ? "N/A" : String.format("%.2f%%", processCpu * 100),
                "systemCpuUsage", systemCpu == null ? "N/A" : String.format("%.2f%%", systemCpu * 100),
                "cpuCount", cpuCount == null ? "N/A" : cpuCount.intValue()
        );
    }

    public Map<String, Object> queryRange(String promql, Instant start, Instant end, Duration step) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", promql);
        result.put("start", start == null ? "" : start.toString());
        result.put("end", end == null ? "" : end.toString());
        result.put("stepSeconds", step == null ? 60 : step.toSeconds());
        if (promql == null || promql.isBlank() || start == null || end == null) {
            result.put("series", List.of());
            result.put("error", "query/start/end are required");
            return result;
        }
        try {
            JSONObject json = queryPath("/api/v1/query_range?query=" + encode(promql)
                    + "&start=" + encode(start.toString())
                    + "&end=" + encode(end.toString())
                    + "&step=" + Math.max(1, step == null ? 60 : step.toSeconds()));
            result.put("status", json.getString("status"));
            JSONObject data = json.getJSONObject("data");
            result.put("series", data == null || data.getJSONArray("result") == null
                    ? List.of()
                    : data.getJSONArray("result"));
            return result;
        } catch (Exception ex) {
            result.put("series", List.of());
            result.put("error", ex.getMessage());
            return result;
        }
    }

    public String evaluateHealth(Double heapUsed, Double heapMax, Double cpu, Double threads) {
        if (heapUsed != null && heapMax != null && heapMax > 0) {
            double usage = heapUsed / heapMax;
            if (usage > 0.9) {
                return "MEMORY_ALERT";
            }
            if (usage > 0.8) {
                return "MEMORY_HIGH";
            }
        }
        if (cpu != null && cpu > 0.9) {
            return "CPU_ALERT";
        }
        if (cpu != null && cpu > 0.8) {
            return "CPU_HIGH";
        }
        if (threads != null && threads > 500) {
            return "THREAD_HIGH";
        }
        return "HEALTHY";
    }

    private JSONObject queryPath(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(prometheusUrl + path))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return JSON.parseObject(response.body());
        } catch (Exception ex) {
            return new JSONObject();
        }
    }

    private JSONObject executePromql(String query) {
        return queryPath("/api/v1/query?query=" + encode(query));
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private Double querySingleMetric(String query) {
        try {
            JSONObject json = executePromql(query);
            JSONArray results = json.getJSONObject("data").getJSONArray("result");
            if (results == null || results.isEmpty()) {
                return null;
            }
            return Double.parseDouble(results.getJSONObject(0).getJSONArray("value").getString(1));
        } catch (Exception ex) {
            return null;
        }
    }

    private Map<String, Double> queryMetricByLabel(String query, String label) {
        Map<String, Double> result = new LinkedHashMap<>();
        try {
            JSONObject json = executePromql(query);
            JSONArray results = json.getJSONObject("data").getJSONArray("result");
            if (results == null) {
                return result;
            }
            for (int i = 0; i < results.size(); i++) {
                JSONObject item = results.getJSONObject(i);
                String key = item.getJSONObject("metric").getString(label);
                result.put(key, Double.parseDouble(item.getJSONArray("value").getString(1)));
            }
        } catch (Exception ignored) {
        }
        return result;
    }

}
