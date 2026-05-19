package org.javaup.ai.rag.channel;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class SearchContext {
    private String originalQuery;
    private String rewrittenQuery;
    private List<String> queryVariants;
    private int topK;
    private boolean enableRerank;
    private String runId;
    private Map<String, Object> metadata;
}
