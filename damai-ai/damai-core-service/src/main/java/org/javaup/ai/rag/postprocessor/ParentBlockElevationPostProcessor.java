package org.javaup.ai.rag.postprocessor;

import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.rag.channel.SearchContext;
import org.javaup.ai.vo.RagSourceVo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Elevates child chunks to their parent blocks after retrieval.
 * A parent's aggregated score = bestChildScore * (1 + supportWeight + multiChannelWeight).
 * This ensures more complete context blocks are returned to the LLM.
 *
 * Inspired by super-agent's parent-child block elevation.
 */
@Slf4j
@Component
public class ParentBlockElevationPostProcessor implements SearchResultPostProcessor {

    @Value("${damai.ai.retrieval.parent-elevation-enabled:true}")
    private boolean enabled;

    @Override
    public String name() { return "parent-block-elevation"; }

    @Override
    public int order() { return 15; }

    @Override
    public List<RagSourceVo> process(List<RagSourceVo> sources, SearchContext context) {
        if (!enabled || sources == null || sources.isEmpty()) return sources;

        Map<String, List<RagSourceVo>> childrenByParent = new LinkedHashMap<>();
        for (RagSourceVo s : sources) {
            String parentId = s.getParentBlockId();
            if (parentId == null || parentId.isBlank()) {
                parentId = s.getChunkId();
            }
            childrenByParent.computeIfAbsent(parentId, k -> new ArrayList<>()).add(s);
        }

        List<RagSourceVo> elevated = new ArrayList<>();
        for (var entry : childrenByParent.entrySet()) {
            List<RagSourceVo> children = entry.getValue();
            if (children.size() == 1) {
                elevated.add(children.get(0));
                continue;
            }
            double bestScore = children.stream()
                    .filter(s -> s.getScore() != null)
                    .mapToDouble(RagSourceVo::getScore)
                    .max().orElse(0D);
            int supportWeight = children.size() - 1;
            long channelCount = children.stream()
                    .map(s -> s.getChannelName() != null ? s.getChannelName() : s.getSource())
                    .filter(Objects::nonNull)
                    .distinct()
                    .count();
            int multiChannelBonus = channelCount > 1 ? 1 : 0;
            double aggregatedScore = bestScore * (1.0 + supportWeight * 0.15 + multiChannelBonus * 0.25);

            RagSourceVo best = children.stream()
                    .filter(s -> s.getScore() != null && s.getScore() == bestScore)
                    .findFirst().orElse(children.get(0));
            RagSourceVo elevatedBlock = RagSourceVo.builder()
                    .chunkId(best.getChunkId())
                    .title(best.getTitle())
                    .source(best.getSource())
                    .section(best.getSection())
                    .snippet(best.getSnippet())
                    .score(aggregatedScore)
                    .parentBlockId(best.getParentBlockId())
                    .channelName(best.getChannelName())
                    .validUntil(best.getValidUntil())
                    .version(best.getVersion())
                    .scope(best.getScope())
                    .topic(best.getTopic())
                    .documentId(best.getDocumentId())
                    .audience(best.getAudience())
                    .region(best.getRegion())
                    .docStatus(best.getDocStatus())
                    .build();
            elevated.add(elevatedBlock);
        }
        elevated.sort(Comparator.comparingDouble(s -> s.getScore() != null ? -s.getScore() : 0));
        log.debug("Parent-block elevation: {} chunks → {} parent blocks", sources.size(), elevated.size());
        return elevated;
    }
}
