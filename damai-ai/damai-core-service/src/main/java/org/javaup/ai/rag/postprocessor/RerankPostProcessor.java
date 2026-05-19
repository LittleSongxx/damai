package org.javaup.ai.rag.postprocessor;

import cn.hutool.core.collection.CollectionUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.rag.channel.SearchContext;
import org.javaup.ai.service.RerankService;
import org.javaup.ai.vo.RagSourceVo;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Re-ranks fused results using a cross-encoder model.
 * Only active when enableRerank=true in the search context.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RerankPostProcessor implements SearchResultPostProcessor {

    private final RerankService rerankService;
    private final org.javaup.ai.service.HybridSearchService hybridSearchService;

    @Override
    public String name() { return "rerank"; }

    @Override
    public int order() { return 20; }

    @Override
    public List<RagSourceVo> process(List<RagSourceVo> sources, SearchContext context) {
        if (!context.isEnableRerank() || CollectionUtil.isEmpty(sources)) {
            return sources;
        }
        try {
            List<Document> docs = hybridSearchService.resolveDocuments(sources);
            if (CollectionUtil.isEmpty(docs)) return sources;
            List<Document> reranked = rerankService.rerank(context.getRewrittenQuery(), docs,
                    Math.min(context.getTopK(), docs.size()));
            if (CollectionUtil.isEmpty(reranked)) return sources;
            Map<String, RagSourceVo> sourceMap = new LinkedHashMap<>();
            for (RagSourceVo s : sources) {
                if (s.getChunkId() != null) sourceMap.putIfAbsent(s.getChunkId(), s);
            }
            return reranked.stream()
                    .map(d -> sourceMap.get(chunkId(d)))
                    .filter(Objects::nonNull)
                    .toList();
        } catch (Exception e) {
            log.warn("Rerank failed, returning original order: {}", e.getMessage());
            return sources;
        }
    }

    private String chunkId(Document doc) {
        Object v = doc.getMetadata().get("chunkId");
        return v != null ? String.valueOf(v) : doc.getId();
    }
}
