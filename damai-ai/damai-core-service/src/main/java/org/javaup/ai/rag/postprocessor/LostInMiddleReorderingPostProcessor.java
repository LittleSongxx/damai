package org.javaup.ai.rag.postprocessor;

import org.javaup.ai.rag.channel.SearchContext;
import org.javaup.ai.vo.RagSourceVo;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Reorders retrieved documents to mitigate the "Lost in the Middle" problem.
 *
 * LLMs pay most attention to content at the start and end of the context window.
 * This post-processor places the highest-relevance documents at both ends,
 * pushing lower-relevance documents to the middle where they cause less harm.
 *
 * Order: [best, 2nd, 4th, 5th, ..., 3rd] — best at start, 2nd-best at end
 */
@Component
public class LostInMiddleReorderingPostProcessor implements SearchResultPostProcessor {

    @Override
    public String name() { return "lost-in-middle-reorder"; }

    @Override
    public int order() { return 25; }

    @Override
    public List<RagSourceVo> process(List<RagSourceVo> sources, SearchContext context) {
        if (sources == null || sources.size() <= 3) {
            return sources;
        }

        List<RagSourceVo> reordered = new ArrayList<>(sources.size());

        // Interleave: best first, second-best last, then fill middle
        int left = 0;
        int right = sources.size() - 1;
        boolean takeFromLeft = true;

        for (int i = 0; i < sources.size(); i++) {
            if (takeFromLeft) {
                reordered.add(sources.get(left));
                left++;
            } else {
                reordered.add(sources.get(right));
                right--;
            }
            takeFromLeft = !takeFromLeft;
        }

        return reordered;
    }
}
