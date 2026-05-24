package org.javaup.ai.rag.engine;

import org.javaup.ai.rag.channel.DenseSearchChannel;
import org.javaup.ai.rag.channel.HydeSearchChannel;
import org.javaup.ai.rag.channel.SearchChannel;
import org.javaup.ai.rag.channel.SearchContext;
import org.javaup.ai.rag.channel.SparseSearchChannel;
import org.javaup.ai.rag.postprocessor.EvidenceGatePostProcessor;
import org.javaup.ai.rag.postprocessor.SearchResultPostProcessor;
import org.javaup.ai.service.AdvancedQueryService;
import org.javaup.ai.vo.RagSearchResultVo;
import org.javaup.ai.vo.RagSourceVo;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MultiChannelRetrievalEngineTest {

    @Test
    void shouldApplyEvidenceGateBeforeRrfFusion() {
        DenseSearchChannel denseChannel = mock(DenseSearchChannel.class);
        SparseSearchChannel sparseChannel = mock(SparseSearchChannel.class);
        HydeSearchChannel hydeChannel = mock(HydeSearchChannel.class);

        when(denseChannel.search(any())).thenReturn(CompletableFuture.completedFuture(
                new SearchChannel.SearchChannelResult("dense", List.of(
                        source("chunk-a", 0.91, "dense"),
                        source("chunk-b", 0.82, "dense")
                ), 1)));
        when(hydeChannel.search(any())).thenReturn(CompletableFuture.completedFuture(
                new SearchChannel.SearchChannelResult("hyde", List.of(), 1)));
        when(sparseChannel.search(any())).thenReturn(CompletableFuture.completedFuture(
                new SearchChannel.SearchChannelResult("sparse", List.of(
                        source("chunk-a", 8.0, "sparse"),
                        source("chunk-c", 7.0, "sparse")
                ), 1)));

        EvidenceGatePostProcessor evidenceGate = evidenceGate(0.45, 0.35);
        MultiChannelRetrievalEngine engine = new MultiChannelRetrievalEngine(
                denseChannel, sparseChannel, hydeChannel, List.of(evidenceGate));
        ReflectionTestUtils.setField(engine, "candidateMultiplier", 5);
        ReflectionTestUtils.setField(engine, "maxCandidates", 50);
        ReflectionTestUtils.setField(engine, "rrfK", 60);

        SearchContext context = SearchContext.builder()
                .originalQuery("退票规则")
                .rewrittenQuery("退票规则")
                .queryVariants(List.of("退票规则"))
                .queryType(AdvancedQueryService.QueryType.MIXED)
                .topK(2)
                .enableRerank(false)
                .build();

        RagSearchResultVo result = engine.retrieve(context);

        assertFalse(result.getSources().isEmpty());
        assertEquals("chunk-a", result.getSources().get(0).getChunkId());
    }

    @Test
    void shouldKeepWiderCandidatePoolBeforePostProcessors() {
        DenseSearchChannel denseChannel = mock(DenseSearchChannel.class);
        SparseSearchChannel sparseChannel = mock(SparseSearchChannel.class);
        HydeSearchChannel hydeChannel = mock(HydeSearchChannel.class);

        when(denseChannel.search(any())).thenReturn(CompletableFuture.completedFuture(
                new SearchChannel.SearchChannelResult("dense", List.of(
                        source("chunk-1", 0.95, "dense"),
                        source("chunk-2", 0.94, "dense"),
                        source("chunk-3", 0.93, "dense"),
                        source("chunk-4", 0.92, "dense"),
                        source("chunk-5", 0.91, "dense"),
                        source("chunk-6", 0.90, "dense"),
                        source("chunk-7", 0.89, "dense"),
                        source("chunk-8", 0.88, "dense")
                ), 1)));
        when(hydeChannel.search(any())).thenReturn(CompletableFuture.completedFuture(
                new SearchChannel.SearchChannelResult("hyde", List.of(), 1)));
        when(sparseChannel.search(any())).thenReturn(CompletableFuture.completedFuture(
                new SearchChannel.SearchChannelResult("sparse", List.of(), 1)));

        EvidenceGatePostProcessor evidenceGate = evidenceGate(0.45, 0.35);
        AtomicReference<Integer> postProcessorInputSize = new AtomicReference<>(0);
        SearchResultPostProcessor capture = new SearchResultPostProcessor() {
            @Override
            public String name() {
                return "capture";
            }

            @Override
            public int order() {
                return 10;
            }

            @Override
            public List<RagSourceVo> process(List<RagSourceVo> sources, SearchContext context) {
                postProcessorInputSize.set(sources.size());
                return sources;
            }
        };

        MultiChannelRetrievalEngine engine = new MultiChannelRetrievalEngine(
                denseChannel, sparseChannel, hydeChannel, List.of(evidenceGate, capture));
        ReflectionTestUtils.setField(engine, "candidateMultiplier", 10);
        ReflectionTestUtils.setField(engine, "maxCandidates", 50);
        ReflectionTestUtils.setField(engine, "rrfK", 60);

        SearchContext context = SearchContext.builder()
                .originalQuery("实名购票")
                .rewrittenQuery("实名购票")
                .queryVariants(List.of("实名购票"))
                .queryType(AdvancedQueryService.QueryType.KEYWORD)
                .topK(1)
                .enableRerank(false)
                .build();

        RagSearchResultVo result = engine.retrieve(context);

        assertEquals(10, context.getCandidateTopK());
        assertEquals(8, postProcessorInputSize.get());
        assertEquals(1, result.getSources().size());
        assertEquals("chunk-1", result.getSources().get(0).getChunkId());
    }

    private EvidenceGatePostProcessor evidenceGate(double minVectorSimilarity, double keywordRelativeScoreFloor) {
        EvidenceGatePostProcessor processor = new EvidenceGatePostProcessor();
        ReflectionTestUtils.setField(processor, "minVectorSimilarity", minVectorSimilarity);
        ReflectionTestUtils.setField(processor, "keywordRelativeScoreFloor", keywordRelativeScoreFloor);
        return processor;
    }

    private RagSourceVo source(String chunkId, double score, String channelName) {
        return RagSourceVo.builder()
                .chunkId(chunkId)
                .title(chunkId)
                .source(channelName + ".md")
                .section("section")
                .snippet("snippet-" + chunkId)
                .score(score)
                .channelName(channelName)
                .build();
    }
}
