package org.javaup.ai.assistant.skill.general;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.config.WebSearchProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@RequiredArgsConstructor
public class TavilyWebSearchClient implements WebSearchClient {

    private final WebSearchProperties properties;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final AtomicInteger keyCursor = new AtomicInteger();

    @Override
    public String provider() {
        return "tavily";
    }

    @Override
    public boolean available() {
        WebSearchProperties.Provider provider = properties.getTavily();
        return properties.isEnabled()
                && provider != null
                && provider.isEnabled()
                && !apiKeys().isEmpty()
                && StringUtils.hasText(provider.getUrl());
    }

    @Override
    public WebSearchResult search(WebSearchRequest request) {
        if (!available()) {
            return WebSearchResult.disabled("Tavily 搜索未配置");
        }
        List<String> apiKeys = apiKeys();
        List<String> errors = new ArrayList<>();
        int startIndex = Math.floorMod(keyCursor.getAndIncrement(), apiKeys.size());
        for (int offset = 0; offset < apiKeys.size(); offset++) {
            String apiKey = apiKeys.get((startIndex + offset) % apiKeys.size());
            try {
                return searchWithKey(request, apiKey);
            } catch (RuntimeException ex) {
                errors.add(ex.getMessage());
            }
        }
        throw new IllegalStateException("Tavily 搜索 key 池全部失败: " + String.join("；", errors));
    }

    private WebSearchResult searchWithKey(WebSearchRequest request, String apiKey) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("api_key", apiKey);
            payload.put("query", request.getQuery());
            payload.put("search_depth", "basic");
            payload.put("include_answer", false);
            payload.put("max_results", maxResults(request));
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(properties.getTavily().getUrl()))
                    .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toJSONString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Tavily 搜索失败: " + response.statusCode());
            }
            List<WebSearchDocument> documents = parseDocuments(response.body());
            return documents.isEmpty() ? WebSearchResult.empty(provider(), "Tavily 未返回搜索结果") : WebSearchResult.success(provider(), documents);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Tavily 搜索被中断", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("Tavily 搜索异常: " + ex.getMessage(), ex);
        }
    }

    private List<String> apiKeys() {
        WebSearchProperties.Provider provider = properties.getTavily();
        if (provider == null) {
            return List.of();
        }
        Set<String> keys = new LinkedHashSet<>();
        if (StringUtils.hasText(provider.getApiKey())) {
            keys.add(provider.getApiKey().trim());
        }
        if (provider.getApiKeys() != null) {
            provider.getApiKeys().stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .forEach(keys::add);
        }
        return List.copyOf(keys);
    }

    private int maxResults(WebSearchRequest request) {
        Integer maxResults = request == null ? null : request.getMaxResults();
        if (maxResults == null || maxResults <= 0) {
            return Math.max(1, properties.getMaxResults());
        }
        return Math.max(1, maxResults);
    }

    private List<WebSearchDocument> parseDocuments(String body) {
        JSONObject root = JSON.parseObject(body);
        JSONArray results = root == null ? null : root.getJSONArray("results");
        List<WebSearchDocument> documents = new ArrayList<>();
        if (results == null) {
            return documents;
        }
        for (int index = 0; index < results.size(); index++) {
            JSONObject item = results.getJSONObject(index);
            if (item == null) {
                continue;
            }
            documents.add(WebSearchDocument.builder()
                    .title(firstText(item, "title", "name"))
                    .url(firstText(item, "url", "link"))
                    .snippet(firstText(item, "content", "snippet", "summary", "description"))
                    .source(provider())
                    .score(item.getDouble("score"))
                    .build());
        }
        return documents;
    }

    private String firstText(JSONObject item, String... fields) {
        for (String field : fields) {
            String value = item.getString(field);
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return "";
    }
}
