package org.javaup.ai.rag.postprocessor;

import org.javaup.ai.vo.RagSourceVo;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagPostProcessorTest {

    @Test
    void shouldPlaceSecondBestSourceAtContextEnd() {
        LostInMiddleReorderingPostProcessor processor = new LostInMiddleReorderingPostProcessor();

        List<RagSourceVo> reordered = processor.process(List.of(
                source("chunk-1", 0.95, "dense"),
                source("chunk-2", 0.90, "dense"),
                source("chunk-3", 0.85, "dense"),
                source("chunk-4", 0.80, "dense"),
                source("chunk-5", 0.75, "dense")
        ), null);

        assertEquals(List.of("chunk-1", "chunk-3", "chunk-4", "chunk-5", "chunk-2"),
                reordered.stream().map(RagSourceVo::getChunkId).toList());
    }

    @Test
    void shouldPreserveMetadataWhenElevatingParentBlock() {
        ParentBlockElevationPostProcessor processor = new ParentBlockElevationPostProcessor();
        ReflectionTestUtils.setField(processor, "enabled", true);

        List<RagSourceVo> elevated = processor.process(List.of(
                RagSourceVo.builder()
                        .chunkId("child-a")
                        .title("Refund")
                        .source("faq.md")
                        .section("rules")
                        .snippet("best child")
                        .score(0.8)
                        .parentBlockId("parent-1")
                        .channelName("dense")
                        .validUntil(987654321L)
                        .version(3)
                        .scope("ticket")
                        .topic("refund")
                        .documentId("doc-refund")
                        .audience("user")
                        .region("cn")
                        .docStatus("published")
                        .build(),
                RagSourceVo.builder()
                        .chunkId("child-b")
                        .title("Refund")
                        .source("faq.md")
                        .section("rules")
                        .snippet("supporting child")
                        .score(0.7)
                        .parentBlockId("parent-1")
                        .channelName("sparse")
                        .build()
        ), null);

        assertEquals(1, elevated.size());
        RagSourceVo source = elevated.get(0);
        assertEquals("child-a", source.getChunkId());
        assertTrue(source.getScore() > 0.8);
        assertEquals("parent-1", source.getParentBlockId());
        assertEquals("dense", source.getChannelName());
        assertEquals(987654321L, source.getValidUntil());
        assertEquals(3, source.getVersion());
        assertEquals("ticket", source.getScope());
        assertEquals("refund", source.getTopic());
        assertEquals("doc-refund", source.getDocumentId());
        assertEquals("user", source.getAudience());
        assertEquals("cn", source.getRegion());
        assertEquals("published", source.getDocStatus());
    }

    private RagSourceVo source(String chunkId, double score, String channel) {
        return RagSourceVo.builder()
                .chunkId(chunkId)
                .title(chunkId)
                .source(channel + ".md")
                .snippet("snippet-" + chunkId)
                .score(score)
                .channelName(channel)
                .build();
    }
}
