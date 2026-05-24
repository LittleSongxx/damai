package org.javaup.ai.rag.channel;

import lombok.Builder;
import lombok.Data;
import org.javaup.ai.service.AdvancedQueryService;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class SearchContext {
    private String originalQuery;
    private String rewrittenQuery;
    private List<String> queryVariants;
    private AdvancedQueryService.QueryType queryType;
    private int topK;
    private int candidateTopK;
    private boolean enableRerank;
    private String runId;
    private Map<String, Object> metadata;
    /** Epoch millis used as the reference time for temporal validity filtering. */
    private Long now;
}
