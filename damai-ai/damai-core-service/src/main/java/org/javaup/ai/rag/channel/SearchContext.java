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
    private KnowledgeRetrievalFilter filter;
    private String scope;
    private String topic;
    private List<String> documentIds;
    private String audience;
    private String region;
    private String channel;
    private String userScope;
    /** Epoch millis used as the reference time for temporal validity filtering. */
    private Long now;

    public KnowledgeRetrievalFilter effectiveFilter() {
        KnowledgeRetrievalFilter explicit = filter == null ? KnowledgeRetrievalFilter.empty() : filter;
        KnowledgeRetrievalFilter inline = KnowledgeRetrievalFilter.builder()
                .scope(scope)
                .topic(topic)
                .documentIds(documentIds)
                .audience(audience)
                .region(region)
                .channel(channel)
                .userScope(userScope)
                .validAt(now)
                .build();
        return explicit.merge(inline);
    }
}
