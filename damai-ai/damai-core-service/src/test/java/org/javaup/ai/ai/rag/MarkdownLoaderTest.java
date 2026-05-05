package org.javaup.ai.ai.rag;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldSplitMarkdownByFaqHeadingAndAttachMetadata() throws Exception {
        Path file = tempDir.resolve("节目退票-相关问题与回答.md");
        Files.writeString(file, """
                # 节目退票-相关问题与回答

                ## 退票多久到账？

                退款一般按原支付路径退回，具体到账时间以支付渠道和项目规则为准。

                ## 不支持退的项目还能退吗？

                若项目明确展示不支持退，通常不支持因个人原因申请退票。
                """, StandardCharsets.UTF_8);
        MarkdownLoader loader = new MarkdownLoader(new PathMatchingResourcePatternResolver(), file.toUri().toString(), 400, 50, 5, 10000, 1000);

        List<Document> documents = loader.loadMarkdowns();

        assertEquals(2, documents.size());
        Document first = documents.get(0);
        assertTrue(first.getText().contains("问题：退票多久到账？"));
        assertTrue(first.getText().contains("答案：退款一般按原支付路径退回"));
        assertEquals("节目退票-相关问题与回答", first.getMetadata().get("docTitle"));
        assertEquals("退票多久到账？", first.getMetadata().get("question"));
        assertEquals("节目退票-相关问题与回答 > 退票多久到账？", first.getMetadata().get("headingPath"));
        assertEquals("faq", first.getMetadata().get("chunkType"));
        assertEquals("节目退票", first.getMetadata().get("label"));
        assertTrue(String.valueOf(first.getMetadata().get("keywords")).contains("退款"));
        assertTrue(String.valueOf(first.getMetadata().get("searchText")).contains("退票多久到账？"));
        assertTrue(String.valueOf(first.getMetadata().get("chunkId")).length() > 16);
        assertEquals(0, first.getMetadata().get("sequence"));
        assertEquals(1, documents.get(1).getMetadata().get("sequence"));
        assertEquals(1, loader.getLastLoadStats().fileCount());
        assertEquals(2, loader.getLastLoadStats().faqCount());
        assertEquals(2, loader.getLastLoadStats().chunkCount());
    }

    @Test
    void shouldKeepChunkIdStableWhenFaqOrderChanges() throws Exception {
        Path firstDir = Files.createDirectory(tempDir.resolve("first"));
        Path secondDir = Files.createDirectory(tempDir.resolve("second"));
        Path firstFile = firstDir.resolve("电子票和数字票-相关问题与回答.md");
        Path secondFile = secondDir.resolve("电子票和数字票-相关问题与回答.md");
        String targetFaq = """
                ## 电子票可以直接入场吗？

                是否可以直接入场取决于项目和场馆规则。
                """;
        Files.writeString(firstFile, """
                # 电子票和数字票

                ## 什么是电子票？

                电子票是非纸质票。

                %s
                """.formatted(targetFaq), StandardCharsets.UTF_8);
        Files.writeString(secondFile, """
                # 电子票和数字票

                %s

                ## 什么是电子票？

                电子票是非纸质票。
                """.formatted(targetFaq), StandardCharsets.UTF_8);
        MarkdownLoader firstLoader = new MarkdownLoader(new PathMatchingResourcePatternResolver(), firstFile.toUri().toString(), 400, 50, 5, 10000, 1000);
        MarkdownLoader secondLoader = new MarkdownLoader(new PathMatchingResourcePatternResolver(), secondFile.toUri().toString(), 400, 50, 5, 10000, 1000);

        String firstChunkId = findChunkId(firstLoader.loadMarkdowns(), "电子票可以直接入场吗？");
        String secondChunkId = findChunkId(secondLoader.loadMarkdowns(), "电子票可以直接入场吗？");

        assertNotEquals("", firstChunkId);
        assertEquals(firstChunkId, secondChunkId);
    }

    @Test
    void shouldSplitLongFaqAndKeepQuestionMetadata() throws Exception {
        Path file = tempDir.resolve("长文档-相关问题与回答.md");
        String longAnswer = "请提前查看项目详情页和现场公告。".repeat(160);
        Files.writeString(file, """
                # 长文档-相关问题与回答

                ## 入场需要注意什么？

                %s
                """.formatted(longAnswer), StandardCharsets.UTF_8);
        MarkdownLoader loader = new MarkdownLoader(new PathMatchingResourcePatternResolver(), file.toUri().toString(), 120, 20, 5, 10000, 100);

        List<Document> documents = loader.loadMarkdowns();

        assertTrue(documents.size() > 1);
        assertTrue(documents.stream().allMatch(document -> "入场需要注意什么？".equals(document.getMetadata().get("question"))));
        assertTrue(documents.stream().allMatch(document -> "faq_part".equals(document.getMetadata().get("chunkType"))));
        assertTrue(documents.stream().allMatch(document -> String.valueOf(document.getMetadata().get("searchText")).contains("入场需要注意什么？")));
        assertEquals(documents.size(), loader.getLastLoadStats().chunkCount());
    }

    @Test
    void shouldSkipEmptyFaqSections() throws Exception {
        Path file = tempDir.resolve("空段-相关问题与回答.md");
        Files.writeString(file, """
                # 空段-相关问题与回答

                ## 空问题

                ## 有答案的问题

                这里有答案。
                """, StandardCharsets.UTF_8);
        MarkdownLoader loader = new MarkdownLoader(new PathMatchingResourcePatternResolver(), file.toUri().toString(), 400, 50, 5, 10000, 1000);

        List<Document> documents = loader.loadMarkdowns();

        assertEquals(1, documents.size());
        assertFalse(documents.get(0).getText().contains("空问题"));
        assertTrue(documents.get(0).getText().contains("有答案的问题"));
    }

    private String findChunkId(List<Document> documents, String question) {
        return documents.stream()
                .filter(document -> question.equals(document.getMetadata().get("question")))
                .findFirst()
                .map(document -> String.valueOf(document.getMetadata().get("chunkId")))
                .orElse("");
    }
}
