package org.javaup.ai.assistant.skill.general;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebSearchResult {

    private String provider;

    private String status;

    private String message;

    private List<WebSearchDocument> documents;

    public boolean hasDocuments() {
        return documents != null && !documents.isEmpty();
    }

    public static WebSearchResult success(String provider, List<WebSearchDocument> documents) {
        return WebSearchResult.builder()
                .provider(provider)
                .status("SUCCESS")
                .message("搜索完成")
                .documents(documents)
                .build();
    }

    public static WebSearchResult empty(String provider, String message) {
        return WebSearchResult.builder()
                .provider(provider)
                .status("EMPTY")
                .message(message)
                .documents(List.of())
                .build();
    }

    public static WebSearchResult disabled(String message) {
        return WebSearchResult.builder()
                .provider("none")
                .status("DISABLED")
                .message(message)
                .documents(List.of())
                .build();
    }

    public static WebSearchResult skipped(String message) {
        return WebSearchResult.builder()
                .provider("none")
                .status("SKIPPED")
                .message(message)
                .documents(List.of())
                .build();
    }
}
