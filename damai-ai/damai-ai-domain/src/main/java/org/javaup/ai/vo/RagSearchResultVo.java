package org.javaup.ai.vo;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagSearchResultVo {

    private String originalQuery;

    private String normalizedQuery;

    private String rewrittenQuery;

    private String retrievalTraceId;

    private List<Document> documents;

    private List<RagSourceVo> sources;

    private List<RagSourceVo> denseSources;

    private List<RagSourceVo> sparseSources;

    private List<RagSourceVo> fusedSources;

    private Double confidenceScore;

    private String confidenceLevel;

    private String correctiveAction;

    private Map<String, Object> metadata;
}
