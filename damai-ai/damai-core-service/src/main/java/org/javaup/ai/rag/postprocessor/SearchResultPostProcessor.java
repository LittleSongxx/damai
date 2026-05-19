package org.javaup.ai.rag.postprocessor;

import org.javaup.ai.rag.channel.SearchContext;
import org.javaup.ai.vo.RagSourceVo;

import java.util.List;

/**
 * Post-processor that transforms or filters retrieval results after channel execution
 * but before final evidence selection. Processors are ordered and executed sequentially.
 */
public interface SearchResultPostProcessor {

    String name();

    int order();

    List<RagSourceVo> process(List<RagSourceVo> sources, SearchContext context);
}
