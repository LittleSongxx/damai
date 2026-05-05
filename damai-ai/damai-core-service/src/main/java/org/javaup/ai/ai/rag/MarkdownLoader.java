package org.javaup.ai.ai.rag;

import cn.hutool.crypto.digest.DigestUtil;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.utils.StringUtil;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
/**
 * @program: 大麦-ai智能服务项目。 添加 阿星不是程序员 微信，添加时备注 ai 来获取项目的完整资料 
 * @description: markdown文档读取 dto
 * @author: 阿星不是程序员
 **/
@Slf4j
public class MarkdownLoader {

    private static final String DEFAULT_DOCUMENT_PATTERN = "classpath:datum/*.md";
    private static final int DEFAULT_CHUNK_SIZE = 400;
    private static final int DEFAULT_MIN_CHUNK_SIZE_CHARS = 50;
    private static final int DEFAULT_MIN_CHUNK_LENGTH_TO_EMBED = 5;
    private static final int DEFAULT_MAX_NUM_CHUNKS = 10000;
    private static final int DEFAULT_MIN_DOC_LENGTH_FOR_TOKEN_SPLIT = 1000;

    private final ResourcePatternResolver resourcePatternResolver;
    private final String documentPattern;
    private final int chunkSize;
    private final int minChunkSizeChars;
    private final int minChunkLengthToEmbed;
    private final int maxNumChunks;
    private final int minDocLengthForTokenSplit;
    private volatile LoadStats lastLoadStats = new LoadStats(0, 0, 0, 0);

    public MarkdownLoader(ResourcePatternResolver resourcePatternResolver) {
        this(resourcePatternResolver,
                DEFAULT_DOCUMENT_PATTERN,
                DEFAULT_CHUNK_SIZE,
                DEFAULT_MIN_CHUNK_SIZE_CHARS,
                DEFAULT_MIN_CHUNK_LENGTH_TO_EMBED,
                DEFAULT_MAX_NUM_CHUNKS,
                DEFAULT_MIN_DOC_LENGTH_FOR_TOKEN_SPLIT);
    }

    public MarkdownLoader(ResourcePatternResolver resourcePatternResolver,
                          String documentPattern,
                          int chunkSize,
                          int minChunkSizeChars,
                          int minChunkLengthToEmbed,
                          int maxNumChunks,
                          int minDocLengthForTokenSplit) {
        this.resourcePatternResolver = resourcePatternResolver;
        this.documentPattern = StringUtil.isEmpty(documentPattern) ? DEFAULT_DOCUMENT_PATTERN : documentPattern;
        this.chunkSize = positiveOrDefault(chunkSize, DEFAULT_CHUNK_SIZE);
        this.minChunkSizeChars = positiveOrDefault(minChunkSizeChars, DEFAULT_MIN_CHUNK_SIZE_CHARS);
        this.minChunkLengthToEmbed = positiveOrDefault(minChunkLengthToEmbed, DEFAULT_MIN_CHUNK_LENGTH_TO_EMBED);
        this.maxNumChunks = positiveOrDefault(maxNumChunks, DEFAULT_MAX_NUM_CHUNKS);
        this.minDocLengthForTokenSplit = positiveOrDefault(minDocLengthForTokenSplit, DEFAULT_MIN_DOC_LENGTH_FOR_TOKEN_SPLIT);
    }

    public List<Document> loadMarkdowns() {
        List<Document> documents = new ArrayList<>();
        int faqCount = 0;
        int skippedCount = 0;
        Resource[] resources = new Resource[0];
        try {
            resources = resourcePatternResolver.getResources(documentPattern);
            Arrays.sort(resources, Comparator.comparing(resource -> resource.getFilename() == null ? "" : resource.getFilename()));
            log.info("找到 {} 个Markdown文件", resources.length);
            for (Resource resource : resources) {
                String fileName = safeFileName(resource);
                try {
                    String markdown = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    List<FaqSection> sections = parseFaqSections(fileName, markdown);
                    faqCount += sections.size();
                    for (FaqSection section : sections) {
                        documents.addAll(toDocuments(fileName, section));
                    }
                    log.info("文件 {} 解析出 {} 个FAQ条目", fileName, sections.size());
                } catch (IOException ex) {
                    skippedCount++;
                    log.error("Markdown 文档加载失败: {}", fileName, ex);
                }
            }
        } catch (IOException e) {
           log.error("Markdown 文档加载失败", e);
        }
        attachSequenceMetadata(documents);
        lastLoadStats = new LoadStats(resources.length, faqCount, documents.size(), skippedCount);
        log.info("总共加载 {} 个FAQ条目，生成 {} 个文档片段，跳过 {} 个文件或片段", faqCount, documents.size(), skippedCount);
        return documents;
    }

    public LoadStats getLastLoadStats() {
        return lastLoadStats;
    }

    private List<FaqSection> parseFaqSections(String fileName, String markdown) {
        List<FaqSection> sections = new ArrayList<>();
        String[] lines = markdown.split("\\R", -1);
        String docTitle = extractLabel(fileName);
        String question = null;
        StringBuilder answer = new StringBuilder();
        for (String line : lines) {
            if (isHeading(line, 1)) {
                String title = stripHeading(line);
                if (StringUtil.isNotEmpty(title)) {
                    docTitle = title;
                }
                continue;
            }
            if (isHeading(line, 2)) {
                addSection(sections, docTitle, question, answer.toString());
                question = stripHeading(line);
                answer = new StringBuilder();
                continue;
            }
            if (question != null) {
                answer.append(line).append('\n');
            }
        }
        addSection(sections, docTitle, question, answer.toString());
        if (sections.isEmpty() && StringUtil.isNotEmpty(markdown.trim())) {
            sections.add(new FaqSection(docTitle, docTitle, markdown.trim()));
        }
        return sections;
    }

    private List<Document> toDocuments(String fileName, FaqSection section) {
        String text = "问题：" + section.question() + "\n\n答案：" + section.answer().trim();
        Map<String, Object> metadata = baseMetadata(fileName, section, text);
        if (text.length() <= minDocLengthForTokenSplit) {
            metadata.put("chunkType", "faq");
            metadata.put("partIndex", 0);
            metadata.put("partCount", 1);
            metadata.put("chunkId", chunkId(metadata, text, 0));
            return List.of(new Document(text, metadata));
        }
        TokenTextSplitter splitter = new TokenTextSplitter(chunkSize, minChunkSizeChars, minChunkLengthToEmbed, maxNumChunks, true);
        List<Document> splits = splitter.split(List.of(new Document(text, metadata)));
        List<Document> documents = new ArrayList<>();
        for (int index = 0; index < splits.size(); index++) {
            Document split = splits.get(index);
            Map<String, Object> splitMetadata = new HashMap<>(metadata);
            splitMetadata.put("chunkType", "faq_part");
            splitMetadata.put("partIndex", index);
            splitMetadata.put("partCount", splits.size());
            splitMetadata.put("contentHash", DigestUtil.md5Hex(split.getText() == null ? "" : split.getText()));
            splitMetadata.put("searchText", buildSearchText(section, String.valueOf(splitMetadata.get("keywords")), split.getText()));
            splitMetadata.put("chunkId", chunkId(splitMetadata, split.getText(), index));
            documents.add(new Document(split.getText(), splitMetadata));
        }
        return documents;
    }

    private Map<String, Object> baseMetadata(String fileName, FaqSection section, String text) {
        String label = extractLabel(fileName);
        String keywords = extractKeywords(fileName, section.docTitle(), section.question(), section.answer());
        String headingPath = section.docTitle() + " > " + section.question();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("name", fileName);
        metadata.put("title", section.question());
        metadata.put("label", label);
        metadata.put("keywords", keywords);
        metadata.put("source", "official_faq");
        metadata.put("sourceFile", fileName);
        metadata.put("docTitle", section.docTitle());
        metadata.put("question", section.question());
        metadata.put("section", section.question());
        metadata.put("headingPath", headingPath);
        metadata.put("contentHash", DigestUtil.md5Hex(text));
        metadata.put("searchText", buildSearchText(section, keywords, text));
        metadata.put("loadTime", LocalDateTime.now().toString());
        return metadata;
    }

    private String extractKeywords(String... values) {
        Set<String> keywords = new LinkedHashSet<>();
        Map<String, String> keywordMap = Map.ofEntries(
                Map.entry("退票", "退票,退款,退钱,退改,取消订单,条件退,手续费"),
                Map.entry("退款", "退款,退票,退钱,原路退回,到账"),
                Map.entry("订票", "订票,购票,买票,下单,抢票"),
                Map.entry("购票", "购票,订票,买票,下单,抢票,限购"),
                Map.entry("取消", "取消,作废,延期,退款"),
                Map.entry("实名", "实名,实名认证,身份证,证件,观演人"),
                Map.entry("观演人", "观演人,实名,证件,入场人,购票人"),
                Map.entry("电子票", "电子票,二维码,身份证电子票,数字票,票夹,换票"),
                Map.entry("数字票", "数字票,电子票,转赠,票夹"),
                Map.entry("转赠", "转赠,转票,赠送,数字票"),
                Map.entry("入场", "入场,安检,检票,证件核验,场馆"),
                Map.entry("安检", "安检,禁带,违禁品,摄录设备,液体"),
                Map.entry("儿童", "儿童票,儿童,亲子,身高,年龄,监护人"),
                Map.entry("配送", "配送,快递,收货地址,物流"),
                Map.entry("取票", "取票,自取,现场取票,换票"),
                Map.entry("支付", "支付,付款,超时,重复支付,支付失败"),
                Map.entry("订单", "订单,订单状态,取消订单,支付超时"),
                Map.entry("安全", "安全,防诈骗,验证码,私下交易,非官方渠道")
        );
        for (String value : values) {
            if (StringUtil.isEmpty(value)) {
                continue;
            }
            keywords.add(normalizeFileToken(value));
            for (Map.Entry<String, String> entry : keywordMap.entrySet()) {
                if (value.contains(entry.getKey())) {
                    keywords.addAll(Arrays.asList(entry.getValue().split(",")));
                }
            }
        }
        keywords.remove("");
        return String.join(",", keywords);
    }

    private String extractLabel(String fileName) {
        if (StringUtil.isEmpty(fileName)) {
            return "";
        }
        String normalized = normalizeFileToken(fileName);
        final String[] parts = normalized.split("-");
        if (parts.length > 0 && StringUtil.isNotEmpty(parts[0])) {
            return parts[0];
        }
        return normalized;
    }

    private void attachSequenceMetadata(List<Document> documents) {
        int index = 0;
        for (Document document : documents) {
            document.getMetadata().put("sequence", index++);
        }
    }

    private String buildSearchText(FaqSection section, String keywords, String text) {
        return String.join("\n",
                section.docTitle(),
                section.question(),
                keywords == null ? "" : keywords,
                text == null ? "" : text).trim();
    }

    private String chunkId(Map<String, Object> metadata, String text, int partIndex) {
        return DigestUtil.md5Hex(metadata.get("sourceFile") + ":" + metadata.get("headingPath") + ":" + DigestUtil.md5Hex(text == null ? "" : text) + ":" + partIndex);
    }

    private void addSection(List<FaqSection> sections, String docTitle, String question, String answer) {
        if (StringUtil.isEmpty(question)) {
            return;
        }
        String normalizedAnswer = answer == null ? "" : answer.trim();
        if (StringUtil.isEmpty(normalizedAnswer)) {
            return;
        }
        sections.add(new FaqSection(docTitle, question.trim(), normalizedAnswer));
    }

    private boolean isHeading(String line, int level) {
        String prefix = "#".repeat(level) + " ";
        return line != null && line.startsWith(prefix) && !line.startsWith(prefix + "#");
    }

    private String stripHeading(String line) {
        if (line == null) {
            return "";
        }
        return line.replaceFirst("^#+\\s*", "").trim();
    }

    private String safeFileName(Resource resource) {
        String fileName = resource.getFilename();
        return StringUtil.isEmpty(fileName) ? "faq.md" : fileName;
    }

    private String normalizeFileToken(String value) {
        if (StringUtil.isEmpty(value)) {
            return "";
        }
        return value.replace(".md", "").trim();
    }

    private int positiveOrDefault(int value, int defaultValue) {
        return value > 0 ? value : defaultValue;
    }

    public record LoadStats(int fileCount, int faqCount, int chunkCount, int skippedCount) {
    }

    private record FaqSection(String docTitle, String question, String answer) {
    }
}
