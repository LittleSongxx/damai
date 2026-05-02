package org.javaup.ai.assistant.skill.general;

import lombok.RequiredArgsConstructor;
import org.javaup.ai.config.WebSearchProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class WebSearchService {

    private final WebSearchProperties properties;
    private final TavilyWebSearchClient tavilyWebSearchClient;
    private final BochaWebSearchClient bochaWebSearchClient;

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
            return null;
        }
    }
}
