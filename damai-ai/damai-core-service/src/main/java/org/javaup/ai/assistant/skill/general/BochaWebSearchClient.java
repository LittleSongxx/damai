package org.javaup.ai.assistant.skill.general;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
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
import java.util.List;

@Component
@RequiredArgsConstructor
public class BochaWebSearchClient implements WebSearchClient {

    private final WebSearchProperties properties;
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Override
    public String provider() {
        return "bocha";
    }

    @Override
    public boolean available() {
        WebSearchProperties.Provider provider = properties.getBocha();
        return properties.isEnabled()
                && provider != null
                && provider.isEnabled()
                && StringUtils.hasText(provider.getApiKey())
                && StringUtils.hasText(provider.getUrl());
    }

    @Override
    public WebSearchResult search(WebSearchRequest request) {
        if (!available()) {
            return WebSearchResult.disabled("博查搜索未配置");
        }
        try {
            JSONObject payload = new JSONObject();
            payload.put("query", request.getQuery());
            payload.put("count", maxResults(request));
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(properties.getBocha().getUrl()))
                    .timeout(Duration.ofMillis(properties.getTimeoutMs()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + properties.getBocha().getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toJSONString(), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("博查搜索失败: " + response.statusCode());
            }
            List<WebSearchDocument> documents = parseDocuments(response.body());
            return documents.isEmpty() ? WebSearchResult.empty(provider(), "博查未返回搜索结果") : WebSearchResult.success(provider(), documents);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("博查搜索被中断", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("博查搜索异常: " + ex.getMessage(), ex);
        }
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
        JSONArray results = findResults(root);
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
                    .title(firstText(item, "title", "name", "siteName"))
                    .url(firstText(item, "url", "link"))
                    .snippet(firstText(item, "snippet", "summary", "content", "description"))
                    .source(provider())
                    .score(item.getDouble("score"))
                    .build());
        }
        return documents;
    }

    private JSONArray findResults(JSONObject root) {
        if (root == null) {
            return null;
        }
        JSONArray results = root.getJSONArray("results");
        if (results != null) {
            return results;
        }
        JSONObject data = root.getJSONObject("data");
        if (data == null) {
            return null;
        }
        results = data.getJSONArray("results");
        if (results != null) {
            return results;
        }
        JSONObject webPages = data.getJSONObject("webPages");
        return webPages == null ? null : webPages.getJSONArray("value");
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
