package org.javaup.ai.assistant.skill.general;

import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.cache.WebSearchCacheService;
import org.javaup.ai.config.WebSearchProperties;
import org.javaup.ai.resilience.CircuitBreakerService;
import org.javaup.ai.resilience.DegradationService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebSearchService {

    private final WebSearchProperties properties;
    private final TavilyWebSearchClient tavilyWebSearchClient;
    private final BochaWebSearchClient bochaWebSearchClient;
    private final WebSearchCacheService webSearchCacheService;
    private final CircuitBreakerService circuitBreakerService;
    private final DegradationService degradationService;

    public WebSearchResult search(String query) {
        return search(WebSearchRequest.builder()
                .query(query)
                .maxResults(properties.getMaxResults())
                .build());
    }

    public WebSearchResult search(WebSearchRequest request) {
        if (!properties.isEnabled()) {
            return WebSearchResult.disabled("联网搜索未启用");
        }
        if (request == null || !StringUtils.hasText(request.getQuery())) {
            return WebSearchResult.empty("none", "搜索关键词为空");
        }

        String cacheKey = request.getQuery();
        String cached = webSearchCacheService.get(cacheKey);
        if (cached != null) {
            WebSearchResult cachedResult = JSON.parseObject(cached, WebSearchResult.class);
            if (cachedResult != null) {
                return cachedResult;
            }
        }

        WebSearchResult result = circuitBreakerService.executeWebSearch(
                () -> doSearch(request),
                WebSearchResult.empty("circuit_open", "联网搜索暂不可用"));

        if (result.hasDocuments()) {
            webSearchCacheService.put(cacheKey, JSON.toJSONString(result));
        }
        return result;
    }

    private WebSearchResult doSearch(WebSearchRequest request) {
        List<String> errors = new ArrayList<>();
        boolean attempted = false;
        if (tavilyWebSearchClient.available()) {
            attempted = true;
            WebSearchResult result = trySearch(tavilyWebSearchClient, request, errors);
            if (result != null && result.hasDocuments()) {
                return result;
            }
        }
        if (bochaWebSearchClient.available()) {
            attempted = true;
            WebSearchResult result = trySearch(bochaWebSearchClient, request, errors);
            if (result != null && result.hasDocuments()) {
                return result;
            }
        }
        if (!attempted) {
            return WebSearchResult.disabled("联网搜索供应商未配置 API Key");
        }
        return WebSearchResult.empty("none", errors.isEmpty() ? "未找到可用搜索结果" : String.join("；", errors));
    }

    private WebSearchResult trySearch(WebSearchClient client, WebSearchRequest request, List<String> errors) {
        try {
            WebSearchResult result = client.search(request);
            if (result == null || !result.hasDocuments()) {
                errors.add(client.provider() + " 未返回结果");
            }
            return result;
        } catch (RuntimeException ex) {
            errors.add(client.provider() + " 调用失败: " + ex.getMessage());
            degradationService.begin("web_search").degradedTo(client.provider() + "_skipped");
            return null;
        }
    }
}
