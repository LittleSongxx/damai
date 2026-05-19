package org.javaup.ai.rag.postprocessor;

import org.javaup.ai.rag.channel.SearchContext;
import org.javaup.ai.vo.RagSourceVo;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deduplicates results from multiple channels, keeping the highest score per chunk.
 */
@Component
public class DedupPostProcessor implements SearchResultPostProcessor {

    @Override
    public String name() { return "dedup"; }

    @Override
    public int order() { return 10; }

    @Override
    public List<RagSourceVo> process(List<RagSourceVo> sources, SearchContext context) {
        if (sources == null || sources.isEmpty()) return List.of();
        Map<String, RagSourceVo> deduped = new LinkedHashMap<>();
        for (RagSourceVo source : sources) {
            String cid = source.getChunkId();
            if (cid != null) {
                RagSourceVo existing = deduped.get(cid);
                if (existing == null || (source.getScore() != null &&
                        (existing.getScore() == null || source.getScore() > existing.getScore()))) {
                    deduped.put(cid, source);
                }
            }
        }
        return new ArrayList<>(deduped.values());
    }
}
