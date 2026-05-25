package org.javaup.ai.ai.rag;

import cn.hutool.crypto.digest.DigestUtil;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.utils.StringUtil;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Enhanced Markdown document loader with:
 * - YAML front matter parsing
 * - Hierarchical chunking (parent-child)
 * - Contextual prefix enrichment
 * - Structured metadata extraction
 */
@Slf4j
@Component
public class MarkdownLoader {

    private static final int DEFAULT_CHUNK_SIZE = 400;
    private static final int DEFAULT_MIN_CHUNK_SIZE_CHARS = 50;
    private static final int DEFAULT_MIN_CHUNK_LENGTH_TO_EMBED = 5;
    private static final int DEFAULT_MAX_NUM_CHUNKS = 10000;
    private static final int DEFAULT_MIN_DOC_LENGTH_FOR_TOKEN_SPLIT = 1000;

    // Hierarchical chunking: parent blocks for context window
    private static final int PARENT_CHUNK_SIZE = 1024;

    private final ResourcePatternResolver resourcePatternResolver;
    @Value("${damai.ai.rag.document-pattern:classpath:datum/*.md}")
    private String documentPattern;

    @Value("${damai.ai.rag.chunk-size:400}")
    private int chunkSize;

    @Value("${damai.ai.rag.min-chunk-size-chars:50}")
    private int minChunkSizeChars;

    @Value("${damai.ai.rag.min-chunk-length-to-embed:5}")
    private int minChunkLengthToEmbed;

    @Value("${damai.ai.rag.max-num-chunks:10000}")
    private int maxNumChunks;

    @Value("${damai.ai.rag.min-doc-length-for-token-split:1000}")
    private int minDocLengthForTokenSplit;

    private volatile LoadStats lastLoadStats = new LoadStats(0, 0, 0, 0, 0);
    private volatile String currentIndexVersion;

    public MarkdownLoader(ResourcePatternResolver resourcePatternResolver) {
        this.resourcePatternResolver = resourcePatternResolver;
    }

    private int effectiveChunkSize() { return positiveOrDefault(chunkSize, DEFAULT_CHUNK_SIZE); }
    private int effectiveMinChunkSizeChars() { return positiveOrDefault(minChunkSizeChars, DEFAULT_MIN_CHUNK_SIZE_CHARS); }
    private int effectiveMinChunkLengthToEmbed() { return positiveOrDefault(minChunkLengthToEmbed, DEFAULT_MIN_CHUNK_LENGTH_TO_EMBED); }
    private int effectiveMaxNumChunks() { return positiveOrDefault(maxNumChunks, DEFAULT_MAX_NUM_CHUNKS); }
    private int effectiveMinDocLengthForTokenSplit() { return positiveOrDefault(minDocLengthForTokenSplit, DEFAULT_MIN_DOC_LENGTH_FOR_TOKEN_SPLIT); }

    /**
     * Load all markdown documents with hierarchical chunking.
     * Returns a structured result containing documents, front matter metadata, and the chunk hierarchy.
     */
    public LoadResult loadMarkdownsWithMetadata() {
        List<Document> flatDocuments = new ArrayList<>();
        List<DocumentMetadata> documentMetadatas = new ArrayList<>();
        int faqCount = 0;
        int skippedCount = 0;
        Resource[] resources = new Resource[0];

        String effectivePattern = StringUtil.isEmpty(documentPattern) ? "classpath:datum/*.md" : documentPattern;
        try {
            resources = resourcePatternResolver.getResources(effectivePattern);
            Arrays.sort(resources, Comparator.comparing(resource ->
                    resource.getFilename() == null ? "" : resource.getFilename()));
            log.info("Found {} Markdown files", resources.length);

            for (Resource resource : resources) {
                String fileName = safeFileName(resource);
                try {
                    String rawMarkdown;
                    try (InputStream inputStream = resource.getInputStream()) {
                        rawMarkdown = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                    }

                    ParsedMarkdown parsed = parseMarkdownWithFrontMatter(fileName, rawMarkdown);
                    List<FaqSection> sections = parseFaqSections(fileName, parsed.body());

                    DocumentMetadata docMeta = new DocumentMetadata(
                            DigestUtil.md5Hex(rawMarkdown),
                            fileName,
                            parsed.frontMatter()
                    );
                    documentMetadatas.add(docMeta);
                    faqCount += sections.size();

                    for (FaqSection section : sections) {
                        flatDocuments.addAll(toDocumentsWithHierarchy(fileName, section, docMeta));
                    }
                    log.info("File {} parsed {} FAQ entries", fileName, sections.size());
                } catch (IOException ex) {
                    skippedCount++;
                    log.error("Markdown document load failed: {}", fileName, ex);
                }
            }
        } catch (IOException e) {
            log.error("Markdown document scan failed", e);
        }

        attachSequenceMetadata(flatDocuments);
        setParentBlockIds(flatDocuments);
        // Content-based index version: hash of all document content hashes, stable when content unchanged
        String allHashes = documentMetadatas.stream()
                .map(DocumentMetadata::contentHash)
                .sorted()
                .reduce("", (a, b) -> a + b);
        currentIndexVersion = "v" + DigestUtil.md5Hex(allHashes);
        lastLoadStats = new LoadStats(resources.length, faqCount, flatDocuments.size(), skippedCount,
                documentMetadatas.size());
        log.info("Loaded {} FAQ entries, generated {} chunks ({} documents, indexVersion={}), skipped {} files",
                faqCount, flatDocuments.size(), documentMetadatas.size(), currentIndexVersion, skippedCount);

        return new LoadResult(flatDocuments, documentMetadatas);
    }

    /**
     * Backward-compatible: returns flat document list only.
     */
    public List<Document> loadMarkdownsFlat() {
        return loadMarkdownsWithMetadata().documents();
    }

    /**
     * @deprecated use loadMarkdownsFlat() instead
     */
    @Deprecated
    public List<Document> loadMarkdowns() {
        return loadMarkdownsFlat();
    }

    /**
     * Parse YAML front matter from markdown.
     * Front matter is delimited by --- at the start and end.
     */
    static ParsedMarkdown parseMarkdownWithFrontMatter(String fileName, String markdown) {
        Map<String, Object> frontMatter = new LinkedHashMap<>();
        String body = markdown;

        if (markdown.startsWith("---")) {
            int endIdx = markdown.indexOf("---", 3);
            if (endIdx > 0) {
                String yamlBlock = markdown.substring(3, endIdx).trim();
                body = markdown.substring(endIdx + 3).trim();
                frontMatter = parseYamlBlock(yamlBlock);
            }
        }

        // Default front matter from filename conventions
        frontMatter.putIfAbsent("source", "official_faq");
        frontMatter.putIfAbsent("sourceFile", fileName);
        String label = extractLabel(fileName);
        if (!frontMatter.containsKey("category") && StringUtil.isNotEmpty(label)) {
            frontMatter.putIfAbsent("category", label);
        }

        return new ParsedMarkdown(frontMatter, body);
    }

    private static Map<String, Object> parseYamlBlock(String yaml) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String line : yaml.split("\\R")) {
            int colonIdx = line.indexOf(':');
            if (colonIdx <= 0) continue;
            String key = line.substring(0, colonIdx).trim();
            String value = line.substring(colonIdx + 1).trim();
            if (value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            if (value.startsWith("'") && value.endsWith("'")) {
                value = value.substring(1, value.length() - 1);
            }
            // Parse list: [a, b, c]
            if (value.startsWith("[") && value.endsWith("]")) {
                value = value.substring(1, value.length() - 1).trim();
                result.put(key, Arrays.stream(value.split(",")).map(String::trim).toList());
            } else {
                result.put(key, value);
            }
        }
        return result;
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

    /**
     * Hierarchical chunking with contextual prefix enrichment.
     *
     * For short documents (text <= minDocLengthForTokenSplit):
     *   - Single "faq" chunk with contextual prefix
     *   - Parent chunk = same (self-referencing)
     *
     * For long documents:
     *   - Parent chunks: 1024-token blocks with 128-token overlap (for LLM context)
     *   - Child chunks: 400-token blocks for precise vector retrieval
     *   - Each child chunk carries a contextual prefix: "【{docTitle}】{question}\n\n{chunkText}"
     */
    private List<Document> toDocumentsWithHierarchy(String fileName, FaqSection section, DocumentMetadata docMeta) {
        String rawText = section.question() + "\n\n" + section.answer().trim();
        String contextPrefix = "【" + section.docTitle() + "】" + section.question();
        String contextText = contextPrefix + "\n\n" + rawText;
        Map<String, Object> baseMeta = baseMetadata(fileName, section, rawText, docMeta);

        if (rawText.length() <= effectiveMinDocLengthForTokenSplit()) {
            // Short document: apply overlap-aware splitting for docs near the boundary
            int overlap = Math.max(50, effectiveChunkSize() / 5);
            if (rawText.length() > effectiveMinDocLengthForTokenSplit() * 0.7) {
                // Near-boundary docs: use token splitter with overlap to preserve context
                TokenTextSplitter boundarySplitter = new TokenTextSplitter(
                        effectiveChunkSize(), effectiveMinChunkSizeChars(), effectiveMinChunkLengthToEmbed(), effectiveMaxNumChunks(), true);
                List<Document> boundaryChunks = boundarySplitter.split(List.of(new Document(rawText, new HashMap<>())));
                if (boundaryChunks.size() > 1) {
                    List<Document> docs = new ArrayList<>();
                    for (int i = 0; i < boundaryChunks.size(); i++) {
                        Document chunk = boundaryChunks.get(i);
                        String chunkText = contextPrefix + "\n\n" + chunk.getText();
                        Map<String, Object> meta = new HashMap<>(baseMeta);
                        meta.put("chunkType", "faq");
                        meta.put("partIndex", i);
                        meta.put("partCount", boundaryChunks.size());
                        meta.put("chunkId", chunkId(meta, chunkText, i));
                        meta.put("parentBlockId", meta.get("chunkId"));
                        meta.put("contextText", chunkText);
                        docs.add(new Document(chunkText, meta));
                    }
                    return docs;
                }
            }
            Map<String, Object> meta = new HashMap<>(baseMeta);
            meta.put("chunkType", "faq");
            meta.put("partIndex", 0);
            meta.put("partCount", 1);
            meta.put("chunkId", chunkId(meta, contextText, 0));
            meta.put("parentBlockId", meta.get("chunkId"));
            meta.put("contextText", contextText);

            Document doc = new Document(contextText, meta);
            return List.of(doc);
        }

        // Long document: hierarchical chunking
        // Parent chunks: larger blocks for final context window
        TokenTextSplitter parentSplitter = new TokenTextSplitter(
                PARENT_CHUNK_SIZE, effectiveMinChunkSizeChars(), effectiveMinChunkLengthToEmbed(), effectiveMaxNumChunks(), true);
        List<Document> parentBlocks = parentSplitter.split(List.of(new Document(rawText, new HashMap<>())));

        // Child chunks: smaller blocks for embedding/retrieval
        TokenTextSplitter childSplitter = new TokenTextSplitter(
                effectiveChunkSize(), effectiveMinChunkSizeChars(), effectiveMinChunkLengthToEmbed(), effectiveMaxNumChunks(), true);
        List<Document> childBlocks = childSplitter.split(List.of(new Document(rawText, new HashMap<>())));

        // If splitting produced no parent blocks, fall back to single chunk
        if (parentBlocks.isEmpty()) {
            Map<String, Object> meta = new HashMap<>(baseMeta);
            meta.put("chunkType", "faq");
            meta.put("partIndex", 0);
            meta.put("partCount", 1);
            meta.put("chunkId", chunkId(meta, contextText, 0));
            meta.put("parentBlockId", meta.get("chunkId"));
            meta.put("contextText", contextText);
            return List.of(new Document(contextText, meta));
        }

        List<Document> documents = new ArrayList<>();

        // Create parent documents (for context window in retrieval)
        for (int i = 0; i < parentBlocks.size(); i++) {
            Document parent = parentBlocks.get(i);
            String parentText = contextPrefix + "\n\n" + parent.getText();
            Map<String, Object> parentMeta = new HashMap<>(baseMeta);
            parentMeta.put("chunkType", "parent");
            parentMeta.put("partIndex", i);
            parentMeta.put("partCount", parentBlocks.size());
            parentMeta.put("contentHash", DigestUtil.md5Hex(parentText));
            String parentCid = chunkId(parentMeta, parentText, i);
            parentMeta.put("chunkId", parentCid);
            parentMeta.put("parentBlockId", parentCid);
            parentMeta.put("contextText", parentText);
            parentMeta.put("searchText", buildSearchText(section,
                    String.valueOf(parentMeta.get("keywords")), parentText));
            documents.add(new Document(parentText, parentMeta));
        }

        // Create child documents (for precise vector search)
        // If child splitting yields empty, fall back: children = parents
        List<Document> effectiveChildBlocks = childBlocks.isEmpty() ? parentBlocks : childBlocks;
        for (int i = 0; i < effectiveChildBlocks.size(); i++) {
            Document child = effectiveChildBlocks.get(i);
            String childText = contextPrefix + "\n\n" + child.getText();
            int parentIdx = parentBlocks.size() > 1
                    ? Math.min(i * parentBlocks.size() / effectiveChildBlocks.size(), parentBlocks.size() - 1)
                    : 0;

            Map<String, Object> childMeta = new HashMap<>(baseMeta);
            childMeta.put("chunkType", "faq_part");
            childMeta.put("partIndex", i);
            childMeta.put("partCount", effectiveChildBlocks.size());
            childMeta.put("contentHash", DigestUtil.md5Hex(childText));
            String childCid = chunkId(childMeta, childText, i);

            String parentCid = documents.size() > parentIdx
                    ? String.valueOf(documents.get(parentIdx).getMetadata().get("chunkId"))
                    : childCid;
            childMeta.put("chunkId", childCid);
            childMeta.put("parentBlockId", parentCid);
            childMeta.put("contextText", childText);
            childMeta.put("searchText", buildSearchText(section,
                    String.valueOf(childMeta.get("keywords")), childText));
            documents.add(new Document(childText, childMeta));
        }

        return documents;
    }

    /**
     * Set parent_block_id for sibling chain navigation: prev/next for each parent block.
     */
    private void setParentBlockIds(List<Document> documents) {
        // Group parent blocks and set prev/next links
        Map<String, List<Integer>> parentGroups = new LinkedHashMap<>();
        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            String chunkType = String.valueOf(doc.getMetadata().getOrDefault("chunkType", ""));
            if ("parent".equals(chunkType)) {
                String sourceFile = String.valueOf(doc.getMetadata().getOrDefault("sourceFile", ""));
                parentGroups.computeIfAbsent(sourceFile, k -> new ArrayList<>()).add(i);
            }
        }
        for (List<Integer> indices : parentGroups.values()) {
            for (int j = 0; j < indices.size(); j++) {
                Document doc = documents.get(indices.get(j));
                if (j > 0) {
                    Document prev = documents.get(indices.get(j - 1));
                    doc.getMetadata().put("prevBlockId",
                            prev.getMetadata().get("chunkId"));
                }
                if (j < indices.size() - 1) {
                    Document next = documents.get(indices.get(j + 1));
                    doc.getMetadata().put("nextBlockId",
                            next.getMetadata().get("chunkId"));
                }
            }
        }
    }

    private Map<String, Object> baseMetadata(String fileName, FaqSection section, String text,
                                              DocumentMetadata docMeta) {
        String label = String.valueOf(docMeta.frontMatter().getOrDefault("category",
                extractLabel(fileName)));
        String keywords = extractKeywords(fileName, section.docTitle(), section.question(), section.answer());
        String headingPath = section.docTitle() + " > " + section.question();

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("name", fileName);
        metadata.put("title", section.question());
        metadata.put("label", label);
        metadata.put("keywords", keywords);
        metadata.put("source", docMeta.frontMatter().getOrDefault("source", "official_faq"));
        metadata.put("sourceFile", fileName);
        metadata.put("docTitle", section.docTitle());
        metadata.put("question", section.question());
        metadata.put("section", section.question());
        metadata.put("headingPath", headingPath);
        metadata.put("contentHash", DigestUtil.md5Hex(text));
        metadata.put("searchText", buildSearchText(section, keywords, text));
        metadata.put("loadTime", LocalDateTime.now().toString());
        metadata.put("indexVersion", currentIndexVersion != null ? currentIndexVersion : "loading");
        metadata.put("docVersion", DigestUtil.md5Hex(section.docTitle() + ":" + section.question() + ":" + section.answer()));

        // Propagate document validity and version to chunk payload for temporal filtering
        Object validUntil = docMeta.frontMatter().get("valid_until");
        if (validUntil instanceof String s && !s.isEmpty()) {
            try {
                metadata.put("validUntil", java.sql.Timestamp.valueOf(s + " 23:59:59").getTime());
            } catch (Exception ignored) {}
        }
        Object docVersion = docMeta.frontMatter().get("version");
        if (docVersion instanceof Number n) {
            metadata.put("version", n.intValue());
        }

        // Carry forward front matter into chunk metadata
        docMeta.frontMatter().forEach((k, v) -> {
            if (!metadata.containsKey(k)) {
                metadata.put("fm_" + k, v);
            }
        });
        return metadata;
    }

    private static Map<String, String> defaultKeywordMap() {
        return Map.ofEntries(
                Map.entry("退票", "退票,退款,退钱,退改,取消订单,条件退,手续费"),
                Map.entry("退款", "退款,退票,退钱,原路退回,到账"),
                Map.entry("订票", "订票,购票,买票,下单,抢票"),
                Map.entry("购票", "购票,订票,买票,下单,抢票,限购"),
                Map.entry("取消", "取消,作废,延期,退款"),
                Map.entry("实名", "实名,实名认证,身份证,证件,观演人"),
                Map.entry("观演人", "观演人,实名,证件,入场人,购票人"),
                Map.entry("电子票", "电子票,二维码,身份证电子票,数字票,票夹,换票"),
                Map.entry("数字票", "数字票,电子票,转赠,票夹"),
                Map.entry("转赠", "转赠,转票,赠送,数字票,二手票"),
                Map.entry("入场", "入场,安检,检票,证件核验,场馆"),
                Map.entry("安检", "安检,禁带,违禁品,摄录设备,液体"),
                Map.entry("儿童", "儿童票,儿童,亲子,身高,年龄,监护人"),
                Map.entry("配送", "配送,快递,收货地址,物流"),
                Map.entry("取票", "取票,自取,现场取票,换票"),
                Map.entry("支付", "支付,付款,超时,重复支付,支付失败,花呗,分期"),
                Map.entry("订单", "订单,订单状态,取消订单,支付超时,待支付,已出票"),
                Map.entry("安全", "安全,防诈骗,验证码,私下交易,非官方渠道"),
                Map.entry("场馆", "场馆,交通,停车,座位图,存包,无障碍,场地"),
                Map.entry("会员", "会员,积分,等级,权益,签到,兑换"),
                Map.entry("优惠", "优惠券,促销,折扣,满减,拼团,学生票"),
                Map.entry("账号", "账号,登录,注册,密码,冻结,注销,手机号"),
                Map.entry("投诉", "投诉,售后,客服,发票,举报,申诉"),
                Map.entry("保险", "保险,退票险,理赔,增值服务,座位升级"),
                Map.entry("团购", "团购,企业购票,批量,包场,团建"),
                Map.entry("演唱会", "演唱会,音乐节,演出类型,现场"),
                Map.entry("话剧", "话剧,音乐剧,戏剧,舞台剧,脱口秀"),
                Map.entry("票档", "票档,座位,内场,看台,VIP,选座,连座"),
                Map.entry("票价", "票价,费用,服务费,配送费,价格"),
                Map.entry("抢票", "抢票,限购,缺货登记,候补,开票,售罄"),
                Map.entry("隐私", "隐私,个人信息,用户协议,数据,注销"),
                Map.entry("违规", "违规,封号,处罚,黄牛,假票"),
                Map.entry("护照", "护照,外籍,港澳台,国际,跨境"),
                Map.entry("展览", "展览,体育赛事,直播,线上演出")
        );
    }

    private String extractKeywords(String... values) {
        Set<String> keywords = new LinkedHashSet<>();
        for (String value : values) {
            if (StringUtil.isEmpty(value)) continue;
            keywords.add(normalizeFileToken(value));
            for (Map.Entry<String, String> entry : this.keywordMap.entrySet()) {
                if (value.contains(entry.getKey())) {
                    keywords.addAll(Arrays.asList(entry.getValue().split(",")));
                }
            }
        }
        keywords.remove("");
        return String.join(",", keywords);
    }

    private static String extractLabel(String fileName) {
        if (StringUtil.isEmpty(fileName)) return "";
        String normalized = normalizeFileToken(fileName);
        String[] parts = normalized.split("-");
        if (parts.length > 0 && StringUtil.isNotEmpty(parts[0])) {
            return parts[0];
        }
        return normalized;
    }

    private void attachSequenceMetadata(List<Document> documents) {
        int index = 0;
        for (Document doc : documents) {
            doc.getMetadata().put("sequence", index++);
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
        return DigestUtil.md5Hex(
                metadata.get("sourceFile") + ":" +
                metadata.get("headingPath") + ":" +
                DigestUtil.md5Hex(text == null ? "" : text) + ":" +
                partIndex);
    }

    private void addSection(List<FaqSection> sections, String docTitle, String question, String answer) {
        if (StringUtil.isEmpty(question)) return;
        String normalizedAnswer = answer == null ? "" : answer.trim();
        if (StringUtil.isEmpty(normalizedAnswer)) return;
        sections.add(new FaqSection(docTitle, question.trim(), normalizedAnswer));
    }

    private boolean isHeading(String line, int level) {
        String prefix = "#".repeat(level) + " ";
        return line != null && line.startsWith(prefix) && !line.startsWith(prefix + "#");
    }

    private String stripHeading(String line) {
        if (line == null) return "";
        return line.replaceFirst("^#+\\s*", "").trim();
    }

    private String safeFileName(Resource resource) {
        String fileName = resource.getFilename();
        return StringUtil.isEmpty(fileName) ? "faq.md" : fileName;
    }

    private static String normalizeFileToken(String value) {
        if (StringUtil.isEmpty(value)) return "";
        return value.replace(".md", "").trim();
    }

    private int positiveOrDefault(int value, int defaultValue) {
        return value > 0 ? value : defaultValue;
    }

    public LoadStats getLastLoadStats() {
        return lastLoadStats;
    }

    public String getCurrentIndexVersion() {
        return currentIndexVersion;
    }

    // --- records ---

    public record LoadStats(int fileCount, int faqCount, int chunkCount, int skippedCount, int documentCount) {
    }

    public record FaqSection(String docTitle, String question, String answer) {
    }

    public record ParsedMarkdown(Map<String, Object> frontMatter, String body) {
    }

    public record DocumentMetadata(String contentHash, String sourceFile, Map<String, Object> frontMatter) {
    }

    public record LoadResult(List<Document> documents, List<DocumentMetadata> documentMetadatas) {
    }
}
