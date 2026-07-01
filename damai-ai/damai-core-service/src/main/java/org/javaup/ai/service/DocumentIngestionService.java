package org.javaup.ai.service;

import cn.hutool.crypto.digest.DigestUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Points.PointStruct;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.ai.rag.MarkdownLoader;
import org.javaup.ai.cache.CacheManager;
import org.javaup.ai.entity.RagChunk;
import org.javaup.ai.entity.RagDocument;
import org.javaup.ai.entity.RagIngestionTask;
import org.javaup.ai.mapper.RagChunkMapper;
import org.javaup.ai.mapper.RagDocumentMapper;
import org.javaup.ai.mapper.RagIngestionTaskMapper;
import org.javaup.ai.rag.channel.SearchContext;
import org.javaup.ai.rag.engine.MultiChannelRetrievalEngine;
import org.javaup.ai.vo.RagSearchResultVo;
import org.javaup.ai.vo.RagSourceVo;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central document ingestion service — orchestrates the full ingestion pipeline:
 * parse → chunk → enrich → embed → index → persist metadata.
 */
@Slf4j
@Service
public class DocumentIngestionService {

    private final MarkdownLoader markdownLoader;
    private final OpenAiEmbeddingModel embeddingModel;
    private final QdrantClient qdrantClient;
    private final RagDocumentMapper documentMapper;
    private final RagChunkMapper chunkMapper;
    private final RagIngestionTaskMapper taskMapper;
    private final CacheManager cacheManager;
    private final HypotheticalQuestionService hypotheticalService;
    private final MultiChannelRetrievalEngine retrievalEngine;

    @Value("${damai.ai.qdrant.collection:damai_ai_faq}")
    private String qdrantCollection;

    @Value("${damai.ai.qdrant.alias:damai-ai-faq-alias}")
    private String qdrantAlias;

    @Value("${damai.ai.faq.alias:damai-ai-faq-current}")
    private String faqAlias;

    @Value("${DAMAI_AI_OPENAI_EMBEDDING_DIMENSIONS:1024}")
    private Integer embeddingDimensions;

    @Value("${damai.ai.ingestion.skip-hypothetical-questions:false}")
    private boolean skipHypotheticalQuestions;

    private final EsClientHelper esClient;

    private final Map<String, Document> documentCache = new ConcurrentHashMap<>();

    public DocumentIngestionService(MarkdownLoader markdownLoader,
                                     OpenAiEmbeddingModel embeddingModel,
                                     QdrantClient qdrantClient,
                                     RagDocumentMapper documentMapper,
                                     RagChunkMapper chunkMapper,
                                     RagIngestionTaskMapper taskMapper,
                                     CacheManager cacheManager,
                                     HypotheticalQuestionService hypotheticalService,
                                     @Lazy MultiChannelRetrievalEngine retrievalEngine,
                                     EsClientHelper esClient) {
        this.markdownLoader = markdownLoader;
        this.embeddingModel = embeddingModel;
        this.qdrantClient = qdrantClient;
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.taskMapper = taskMapper;
        this.cacheManager = cacheManager;
        this.hypotheticalService = hypotheticalService;
        this.retrievalEngine = retrievalEngine;
        this.esClient = esClient;
    }

    // ======================== Full Ingestion Pipeline ========================

    public Map<String, Object> reindexAll() {
        return reindexAll("ingest_" + UUID.randomUUID().toString().replace("-", ""));
    }

    public Map<String, Object> reindexAll(String taskId) {
        RagIngestionTask task = createTask(taskId, "full");
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            updateTaskStatus(task, "parsing");
            MarkdownLoader.LoadResult loadResult = markdownLoader.loadMarkdownsWithMetadata();
            List<Document> documents = loadResult.documents();
            List<MarkdownLoader.DocumentMetadata> docMetas = loadResult.documentMetadatas();
            cacheDocuments(documents);

            updateTaskStatus(task, "chunking");
            List<RagDocument> ragDocs = persistDocumentMetadata(docMetas, documents);
            List<RagChunk> ragChunks = persistChunkMetadata(documents, ragDocs);
            task.setTotalChunks(ragChunks.size());
            taskMapper.updateById(task);

            // Hypothetical questions for FAQ chunks (async-friendly, blocking for now)
            if (!skipHypotheticalQuestions) {
                updateTaskStatus(task, "embedding");
                generateHypotheticalQuestions(documents, ragChunks);
            } else {
                updateTaskStatus(task, "embedding");
                log.info("Skipping hypothetical question generation (damai.ai.ingestion.skip-hypothetical-questions=true)");
            }

            // Recreate Qdrant collection
            recreateQdrantCollection();

            // Batch embed and upsert Qdrant
            int qdrantCount = batchUpsertQdrant(documents, ragChunks);
            switchQdrantAlias();
            task.setCompletedChunks(qdrantCount);
            taskMapper.updateById(task);

            // Recreate ES index
            updateTaskStatus(task, "indexing");
            EsReindexResult esResult = recreateEsIndex(documents);

            // Invalidate FAQ search cache
            cacheManager.invalidateFaqSearch();

            updateTaskStatus(task, "completed");
            task.setResultJson(JSON.toJSONString(Map.of(
                    "chunkCount", documents.size(),
                    "qdrantPointCount", qdrantCount,
                    "esDocumentCount", esResult.documentCount(),
                    "physicalIndex", esResult.physicalIndex()
            )));

            MarkdownLoader.LoadStats stats = markdownLoader.getLastLoadStats();
            result.put("taskId", taskId);
            result.put("fileCount", stats.fileCount());
            result.put("faqCount", stats.faqCount());
            result.put("chunkCount", documents.size());
            result.put("documentCount", ragDocs.size());
            result.put("qdrantAlias", qdrantAlias);
            result.put("qdrantPointCount", qdrantCount);
            result.put("faqAlias", faqAlias);
            result.put("physicalIndex", esResult.physicalIndex());
            result.put("esDocumentCount", esResult.documentCount());
            result.put("skippedCount", stats.skippedCount());
        } catch (Exception e) {
            log.error("Ingestion task {} failed", taskId, e);
            updateTaskStatus(task, "failed");
            task.setErrorMessage(e.getMessage());
            result.put("error", e.getMessage());
        }
        taskMapper.updateById(task);
        return result;
    }

    public Map<String, Object> incrementalReindex() {
        return incrementalReindex("incr_" + UUID.randomUUID().toString().replace("-", ""));
    }

    public Map<String, Object> incrementalReindex(String taskId) {
        RagIngestionTask task = createTask(taskId, "incremental");
        Map<String, Object> result = new LinkedHashMap<>();

        try {
            MarkdownLoader.LoadResult loadResult = markdownLoader.loadMarkdownsWithMetadata();
            List<Document> allDocuments = loadResult.documents();
            List<Document> changed = new ArrayList<>();
            List<Document> unchanged = new ArrayList<>();

            for (Document doc : allDocuments) {
                String chunkId = chunkId(doc);
                Document cached = chunkId != null ? documentCache.get(chunkId) : null;
                if (cached == null || !java.util.Objects.equals(
                        cached.getMetadata().get("contentHash"),
                        doc.getMetadata().get("contentHash"))) {
                    changed.add(doc);
                } else {
                    unchanged.add(doc);
                }
            }

            cacheDocuments(allDocuments);
            int qdrantUpserted = 0;
            int esUpserted = 0;

            if (!changed.isEmpty()) {
                List<RagChunk> changedChunks = updateChunkMetadata(changed);
                qdrantUpserted = batchUpsertQdrant(changed, changedChunks);
                esUpserted = bulkUpsertEs(changed);
                cacheManager.invalidateFaqSearch();
            }

            updateTaskStatus(task, "completed");
            result.put("taskId", taskId);
            result.put("totalDocuments", allDocuments.size());
            result.put("changedDocuments", changed.size());
            result.put("unchangedDocuments", unchanged.size());
            result.put("qdrantUpserted", qdrantUpserted);
            result.put("esUpserted", esUpserted);
        } catch (Exception e) {
            log.error("Incremental ingestion task {} failed", taskId, e);
            updateTaskStatus(task, "failed");
            task.setErrorMessage(e.getMessage());
            result.put("error", e.getMessage());
        }
        taskMapper.updateById(task);
        return result;
    }

    // ======================== Document Persistence ========================

    private List<RagDocument> persistDocumentMetadata(
            List<MarkdownLoader.DocumentMetadata> docMetas, List<Document> documents) {
        List<RagDocument> ragDocs = new ArrayList<>();
        for (MarkdownLoader.DocumentMetadata dm : docMetas) {
            RagDocument existing = documentMapper.selectLatestBySourceFile(dm.sourceFile());
            int newVersion = (existing != null ? existing.getVersion() : 0) + 1;

            RagDocument rd = new RagDocument();
            rd.setDocUid(DigestUtil.md5Hex(dm.sourceFile() + ":" + newVersion));
            rd.setTitle(String.valueOf(dm.frontMatter().getOrDefault("title", dm.sourceFile())));
            rd.setSource(String.valueOf(dm.frontMatter().getOrDefault("source", "official_faq")));
            rd.setSourceFile(dm.sourceFile());
            rd.setCategory(String.valueOf(dm.frontMatter().getOrDefault("category", "")));
            rd.setTags(String.valueOf(dm.frontMatter().getOrDefault("tags", "")));
            rd.setDocStatus("published");
            rd.setFileType("md");
            rd.setContentHash(dm.contentHash());
            rd.setMetadataJson(JSON.toJSONString(dm.frontMatter()));
            rd.setVersion(newVersion);
            rd.setCreateTime(new java.util.Date());
            rd.setEditTime(new java.util.Date());
            rd.setStatus(1);

            // Set validity from front matter
            Object validFrom = dm.frontMatter().get("valid_from");
            Object validUntil = dm.frontMatter().get("valid_until");
            if (validFrom instanceof String s && !s.isEmpty()) {
                try { rd.setValidFrom(java.sql.Timestamp.valueOf(s + " 00:00:00")); } catch (Exception ignored) {}
            }
            if (validUntil instanceof String s && !s.isEmpty()) {
                try { rd.setValidUntil(java.sql.Timestamp.valueOf(s + " 23:59:59")); } catch (Exception ignored) {}
            }
            Object region = dm.frontMatter().get("region");
            if (region instanceof List<?> list) {
                rd.setRegion(String.join(",", list.stream().map(Object::toString).toList()));
            } else if (region instanceof String s) {
                rd.setRegion(s);
            }
            Object audience = dm.frontMatter().get("audience");
            if (audience instanceof String s) {
                rd.setAudience(s);
            }
            Object priority = dm.frontMatter().get("priority");
            if (priority instanceof Number n) {
                rd.setPriority(n.intValue());
            }

            // Archive previous version
            if (existing != null && !java.util.Objects.equals(existing.getContentHash(), dm.contentHash())) {
                existing.setDocStatus("archived");
                existing.setEditTime(new java.util.Date());
                documentMapper.updateById(existing);
            }

            documentMapper.insert(rd);
            ragDocs.add(rd);
        }

        // Count chunks per document and update
        Map<String, Long> docIdBySourceFile = new LinkedHashMap<>();
        for (int i = 0; i < ragDocs.size(); i++) {
            docIdBySourceFile.put(ragDocs.get(i).getSourceFile(), ragDocs.get(i).getId());
        }
        Map<String, Integer> chunkCounts = new HashMap<>();
        for (Document doc : documents) {
            String sf = String.valueOf(doc.getMetadata().getOrDefault("sourceFile", ""));
            chunkCounts.merge(sf, 1, Integer::sum);
        }
        for (RagDocument rd : ragDocs) {
            Integer count = chunkCounts.get(rd.getSourceFile());
            if (count != null) {
                rd.setChunkCount(count);
                documentMapper.updateById(rd);
            }
        }

        return ragDocs;
    }

    private List<RagChunk> persistChunkMetadata(List<Document> documents, List<RagDocument> ragDocs) {
        Map<String, Long> docIdBySourceFile = new LinkedHashMap<>();
        for (RagDocument rd : ragDocs) {
            docIdBySourceFile.put(rd.getSourceFile(), rd.getId());
        }

        // Batch load existing chunks by chunkUid to avoid N+1 queries
        Map<String, RagChunk> existingChunkByUid = new HashMap<>();
        for (Document doc : documents) {
            String cid = chunkId(doc);
            if (StringUtils.hasText(cid) && !existingChunkByUid.containsKey(cid)) {
                RagChunk existing = chunkMapper.selectByChunkUid(cid);
                if (existing != null) {
                    existingChunkByUid.put(cid, existing);
                }
            }
        }

        List<RagChunk> chunks = new ArrayList<>();
        for (Document doc : documents) {
            String cid = chunkId(doc);
            if (!StringUtils.hasText(cid)) continue;

            RagChunk rc = existingChunkByUid.getOrDefault(cid, new RagChunk());
            rc.setChunkUid(cid);
            rc.setDocId(docIdBySourceFile.get(
                    String.valueOf(doc.getMetadata().getOrDefault("sourceFile", ""))));
            rc.setChunkType(String.valueOf(doc.getMetadata().getOrDefault("chunkType", "faq")));
            rc.setChunkIndex(Integer.parseInt(String.valueOf(doc.getMetadata().getOrDefault("partIndex", "0"))));
            rc.setTotalChunks(Integer.parseInt(String.valueOf(doc.getMetadata().getOrDefault("partCount", "1"))));
            rc.setHeadingPath(String.valueOf(doc.getMetadata().getOrDefault("headingPath", "")));
            rc.setQuestion(String.valueOf(doc.getMetadata().getOrDefault("question", "")));
            rc.setText(String.valueOf(doc.getMetadata().getOrDefault("contextText", doc.getText())));
            rc.setContextText(doc.getText());
            rc.setContentHash(String.valueOf(doc.getMetadata().getOrDefault("contentHash", "")));
            rc.setMetadataJson(JSON.toJSONString(doc.getMetadata()));
            rc.setEmbeddingCached(false);
            rc.setCreateTime(new java.util.Date());
            rc.setEditTime(new java.util.Date());
            rc.setStatus(1);

            // Parent block relation — look up from batch-loaded map or DB
            Object parentBlockId = doc.getMetadata().get("parentBlockId");
            if (parentBlockId != null && !parentBlockId.equals(cid)) {
                RagChunk parent = existingChunkByUid.get(String.valueOf(parentBlockId));
                if (parent == null) parent = chunkMapper.selectByChunkUid(String.valueOf(parentBlockId));
                if (parent != null) rc.setParentChunkId(parent.getId());
            }
            // Prev/Next block chains
            Object prevBlockId = doc.getMetadata().get("prevBlockId");
            Object nextBlockId = doc.getMetadata().get("nextBlockId");
            if (prevBlockId != null) {
                RagChunk prev = existingChunkByUid.get(String.valueOf(prevBlockId));
                if (prev == null) prev = chunkMapper.selectByChunkUid(String.valueOf(prevBlockId));
                if (prev != null) rc.setPrevChunkId(prev.getId());
            }
            if (nextBlockId != null) {
                RagChunk next = existingChunkByUid.get(String.valueOf(nextBlockId));
                if (next == null) next = chunkMapper.selectByChunkUid(String.valueOf(nextBlockId));
                if (next != null) rc.setNextChunkId(next.getId());
            }

            if (existingChunkByUid.containsKey(cid)) {
                chunkMapper.updateById(rc);
            } else {
                chunkMapper.insert(rc);
            }
            chunks.add(rc);
        }
        return chunks;
    }

    private List<RagChunk> updateChunkMetadata(List<Document> changedDocs) {
        // For incremental updates, find or create doc records
        Map<String, Long> docIdBySourceFile = new LinkedHashMap<>();
        Map<String, MarkdownLoader.DocumentMetadata> newDocMetas = new LinkedHashMap<>();
        for (Document doc : changedDocs) {
            String sf = String.valueOf(doc.getMetadata().getOrDefault("sourceFile", ""));
            if (!docIdBySourceFile.containsKey(sf)) {
                RagDocument rd = documentMapper.selectLatestBySourceFile(sf);
                if (rd != null) {
                    docIdBySourceFile.put(sf, rd.getId());
                } else {
                    // New file: create a document record
                    String contentHash = String.valueOf(doc.getMetadata().getOrDefault("contentHash", ""));
                    Map<String, Object> frontMatter = new LinkedHashMap<>();
                    frontMatter.put("source", doc.getMetadata().getOrDefault("source", "official_faq"));
                    frontMatter.put("category", doc.getMetadata().getOrDefault("label", ""));
                    newDocMetas.put(sf, new MarkdownLoader.DocumentMetadata(contentHash, sf, frontMatter));
                }
            }
        }
        // Create new documents if any
        List<RagDocument> ragDocs = new ArrayList<>();
        if (!newDocMetas.isEmpty()) {
            ragDocs = persistDocumentMetadata(new ArrayList<>(newDocMetas.values()), changedDocs);
            for (RagDocument rd : ragDocs) {
                docIdBySourceFile.put(rd.getSourceFile(), rd.getId());
            }
        }
        // Build doc reference list
        List<RagDocument> allDocs = new ArrayList<>(ragDocs);
        for (var entry : docIdBySourceFile.entrySet()) {
            boolean alreadyAdded = ragDocs.stream().anyMatch(rd -> rd.getSourceFile().equals(entry.getKey()));
            if (!alreadyAdded) {
                RagDocument dummy = new RagDocument();
                dummy.setId(entry.getValue());
                dummy.setSourceFile(entry.getKey());
                allDocs.add(dummy);
            }
        }
        return persistChunkMetadata(changedDocs, allDocs);
    }

    // ======================== Hypothetical Questions ========================

    private void generateHypotheticalQuestions(List<Document> documents, List<RagChunk> chunks) {
        Map<String, Long> chunkIdToDbId = new LinkedHashMap<>();
        for (RagChunk rc : chunks) {
            if ("faq".equals(rc.getChunkType()) || "faq_part".equals(rc.getChunkType())) {
                chunkIdToDbId.put(rc.getChunkUid(), rc.getId());
            }
        }
        int generated = 0;
        for (Document doc : documents) {
            String cid = chunkId(doc);
            Long dbId = chunkIdToDbId.get(cid);
            if (dbId == null) continue;

            RagChunk rc = chunkMapper.selectById(dbId);
            if (rc == null || StringUtils.hasText(rc.getHypotheticalQuestionsJson())) continue;

            try {
                String rawText = doc.getText();
                if (rawText.length() > 2000) {
                    rawText = rawText.substring(0, 2000);
                }
                List<String> questions = hypotheticalService.generateQuestions(rawText);
                if (!questions.isEmpty()) {
                    rc.setHypotheticalQuestionsJson(JSON.toJSONString(questions));
                    chunkMapper.updateById(rc);
                    generated++;
                }
            } catch (Exception e) {
                log.warn("Hypothetical question generation failed for chunk {}: {}", cid, e.getMessage());
            }
        }
        log.info("Generated hypothetical questions for {} chunks", generated);
    }

    // ======================== Qdrant Operations ========================

    private void recreateQdrantCollection() {
        String newCollection = qdrantCollection + "_" + System.currentTimeMillis();
        try {
            qdrantClient.createCollectionAsync(newCollection,
                    VectorParams.newBuilder()
                            .setSize(embeddingDimensions)
                            .setDistance(Distance.Cosine)
                            .build()
            ).get();
            log.info("Qdrant collection '{}' created (dim={}, Cosine)", newCollection, embeddingDimensions);
        } catch (Exception ex) {
            log.error("Qdrant collection creation failed", ex);
            return;
        }
        // Record the new collection name for upserts
        this.currentQdrantCollection = newCollection;
    }

    private volatile String currentQdrantCollection;

    private void switchQdrantAlias() {
        if (currentQdrantCollection == null) return;
        try {
            // Update alias to point to new collection
            qdrantClient.createAliasAsync(qdrantAlias, currentQdrantCollection).get();
            // Delete old collections (keep only the new one)
            var collections = qdrantClient.listCollectionsAsync().get();
            for (String name : collections) {
                if (name.startsWith(qdrantCollection + "_") && !name.equals(currentQdrantCollection)) {
                    qdrantClient.deleteCollectionAsync(name).get();
                }
            }
            log.info("Qdrant alias '{}' switched to collection '{}'", qdrantAlias, currentQdrantCollection);
        } catch (Exception ex) {
            log.error("Qdrant alias switch failed", ex);
        }
    }

    private String qdrantCollection() {
        return currentQdrantCollection != null ? currentQdrantCollection : qdrantCollection;
    }

    private int batchUpsertQdrant(List<Document> documents, List<RagChunk> chunks) {
        Map<String, Long> chunkIdToDbId = new LinkedHashMap<>();
        for (RagChunk rc : chunks) {
            chunkIdToDbId.put(rc.getChunkUid(), rc.getId());
        }

        // Collect valid documents for batch embedding
        List<Document> validDocs = new ArrayList<>();
        String targetCollection = qdrantCollection();
        for (Document doc : documents) {
            String cid = chunkId(doc);
            if (StringUtils.hasText(cid) && StringUtils.hasText(doc.getText())) {
                validDocs.add(doc);
            }
        }

        // Batch embed all texts at once to reduce API calls
        Map<String, float[]> embeddingMap = batchEmbed(validDocs);

        List<PointStruct> points = new ArrayList<>();
        for (Document doc : validDocs) {
            String cid = chunkId(doc);
            float[] vector = embeddingMap.get(cid);
            if (vector == null) continue;
            List<Float> vectorList = new ArrayList<>(vector.length);
            for (float v : vector) vectorList.add(v);

            Map<String, io.qdrant.client.grpc.JsonWithInt.Value> payloadMap = new HashMap<>();
            for (Map.Entry<String, Object> entry : doc.getMetadata().entrySet()) {
                if (entry.getValue() != null) {
                    payloadMap.put(entry.getKey(),
                            io.qdrant.client.ValueFactory.value(String.valueOf(entry.getValue())));
                }
            }
            payloadMap.put("text", io.qdrant.client.ValueFactory.value(doc.getText()));
            String title = doc.getMetadata().getOrDefault("title",
                    doc.getMetadata().getOrDefault("name", "FAQ")).toString();
            payloadMap.put("title", io.qdrant.client.ValueFactory.value(title));
            // Propagate temporal validity for retrieval-time filtering
            Object validFrom = doc.getMetadata().get("fm_valid_from");
            Object validUntil = doc.getMetadata().get("fm_valid_until");
            if (validFrom != null) payloadMap.put("validFrom",
                    io.qdrant.client.ValueFactory.value(String.valueOf(validFrom)));
            if (validUntil != null) payloadMap.put("validUntil",
                    io.qdrant.client.ValueFactory.value(String.valueOf(validUntil)));

            long pointId = cid.hashCode() & 0xFFFFFFFFL;
            points.add(PointStruct.newBuilder()
                    .setId(io.qdrant.client.PointIdFactory.id(pointId))
                    .setVectors(io.qdrant.client.VectorsFactory.vectors(vectorList))
                    .putAllPayload(payloadMap)
                    .build());

            // Update chunk with Qdrant point ID
            Long dbId = chunkIdToDbId.get(cid);
            if (dbId != null) {
                RagChunk rc = chunkMapper.selectById(dbId);
                if (rc != null) {
                    rc.setQdrantPointId(pointId);
                    rc.setEmbeddingCached(true);
                    chunkMapper.updateById(rc);
                }
            }
        }

        if (points.isEmpty()) return 0;
        try {
            qdrantClient.upsertAsync(targetCollection, points).get();
        } catch (Exception ex) {
            log.error("Qdrant bulk upsert failed", ex);
        }
        return points.size();
    }

    // ======================== ES Operations ========================

    private EsReindexResult recreateEsIndex(List<Document> documents) {
        String physicalIndex = "damai-ai-faq-" + System.currentTimeMillis();
        JSONObject mapping = new JSONObject();
        JSONObject properties = new JSONObject();
        properties.put("chunkId", esClient.fieldMapping("keyword"));
        properties.put("source", esClient.fieldMapping("keyword"));
        properties.put("sourceFile", esClient.fieldMapping("keyword"));
        properties.put("chunkType", esClient.fieldMapping("keyword"));
        properties.put("label", esClient.fieldMapping("keyword"));
        properties.put("indexVersion", esClient.fieldMapping("keyword"));
        properties.put("docVersion", esClient.fieldMapping("keyword"));
        properties.put("contentHash", esClient.fieldMapping("keyword"));
        properties.put("validFrom", esClient.fieldMapping("date"));
        properties.put("validUntil", esClient.fieldMapping("date"));
        properties.put("title", esClient.fieldMapping("text"));
        properties.put("docTitle", esClient.fieldMapping("text"));
        properties.put("question", esClient.fieldMapping("text"));
        properties.put("keywords", esClient.fieldMapping("text"));
        properties.put("searchText", esClient.fieldMapping("text"));
        properties.put("text", esClient.fieldMapping("text"));
        mapping.put("properties", properties);
        esClient.execute("/" + physicalIndex, new JSONObject(Map.of("mappings", mapping)).toJSONString(), "PUT");

        StringBuilder bulk = new StringBuilder();
        int docCount = 0;
        for (Document doc : documents) {
            String cid = chunkId(doc);
            if (!StringUtils.hasText(cid) || !StringUtils.hasText(doc.getText())) continue;
            bulk.append(JSON.toJSONString(Map.of("index", Map.of("_index", physicalIndex, "_id", cid)))).append('\n');
            Map<String, Object> source = new HashMap<>(doc.getMetadata());
            source.put("chunkId", cid);
            source.put("title", source.getOrDefault("title", source.getOrDefault("name", "FAQ")));
            source.put("text", doc.getText());
            source.putIfAbsent("searchText", doc.getText());
            // Propagate temporal validity fields for retrieval-time filtering
            Object validFrom = doc.getMetadata().get("fm_valid_from");
            Object validUntil = doc.getMetadata().get("fm_valid_until");
            if (validFrom != null) source.put("validFrom", validFrom);
            if (validUntil != null) source.put("validUntil", validUntil);
            bulk.append(JSON.toJSONString(source)).append('\n');
            docCount++;

            // Update chunk with ES doc ID
            RagChunk rc = chunkMapper.selectByChunkUid(cid);
            if (rc != null) {
                rc.setEsDocId(cid);
                chunkMapper.updateById(rc);
            }
        }
        if (docCount > 0) {
            esClient.bulkPost(bulk.toString());
        }

        // Atomic alias swap
        JSONObject aliasBody = new JSONObject();
        JSONArray actions = new JSONArray();
        actions.add(new JSONObject(Map.of("remove", Map.of("index", "*", "alias", faqAlias, "ignore_unavailable", true))));
        actions.add(new JSONObject(Map.of("add", Map.of("index", physicalIndex, "alias", faqAlias))));
        aliasBody.put("actions", actions);
        esClient.execute("/_aliases", aliasBody.toJSONString(), "POST");

        return new EsReindexResult(physicalIndex, docCount);
    }

    private int bulkUpsertEs(List<Document> documents) {
        if (documents.isEmpty()) return 0;
        StringBuilder bulk = new StringBuilder();
        int count = 0;
        for (Document doc : documents) {
            String cid = chunkId(doc);
            if (!StringUtils.hasText(cid) || !StringUtils.hasText(doc.getText())) continue;
            bulk.append(JSON.toJSONString(Map.of("index", Map.of("_index", faqAlias, "_id", cid)))).append('\n');
            Map<String, Object> source = new HashMap<>(doc.getMetadata());
            source.put("chunkId", cid);
            source.put("title", source.getOrDefault("title", source.getOrDefault("name", "FAQ")));
            source.put("text", doc.getText());
            source.putIfAbsent("searchText", doc.getText());
            Object validFrom = doc.getMetadata().get("fm_valid_from");
            Object validUntil = doc.getMetadata().get("fm_valid_until");
            if (validFrom != null) source.put("validFrom", validFrom);
            if (validUntil != null) source.put("validUntil", validUntil);
            bulk.append(JSON.toJSONString(source)).append('\n');
            count++;
        }
        if (count > 0) {
            esClient.bulkPost(bulk.toString());
        }
        return count;
    }

    // ======================== Task Management ========================

    private RagIngestionTask createTask(String taskId, String taskType) {
        RagIngestionTask existing = taskMapper.selectByTaskId(taskId);
        if (existing != null) {
            existing.setTaskType(taskType);
            existing.setTaskStatus("pending");
            existing.setErrorMessage(null);
            existing.setResultJson(null);
            existing.setFinishedAt(null);
            existing.setEditTime(new java.util.Date());
            taskMapper.updateById(existing);
            return existing;
        }
        RagIngestionTask task = new RagIngestionTask();
        task.setTaskId(taskId);
        task.setTaskType(taskType);
        task.setTaskStatus("pending");
        task.setStartedAt(LocalDateTime.now().toString());
        task.setCreateTime(new java.util.Date());
        task.setEditTime(new java.util.Date());
        task.setStatus(1);
        taskMapper.insert(task);
        return task;
    }

    private void updateTaskStatus(RagIngestionTask task, String status) {
        task.setTaskStatus(status);
        if ("completed".equals(status) || "failed".equals(status)) {
            task.setFinishedAt(LocalDateTime.now().toString());
        }
        task.setEditTime(new java.util.Date());
        taskMapper.updateById(task);
    }

    // ======================== Cache ========================

    public void cacheDocuments(List<Document> documents) {
        documentCache.clear();
        for (Document doc : documents) {
            String cid = chunkId(doc);
            if (StringUtils.hasText(cid)) {
                documentCache.put(cid, doc);
            }
        }
        log.info("Cached {} document chunks", documentCache.size());
    }

    public Document getCachedDocument(String chunkId) {
        if (documentCache.isEmpty()) {
            cacheDocuments(markdownLoader.loadMarkdownsFlat());
        }
        return documentCache.get(chunkId);
    }

    public Map<String, Document> getDocumentCache() {
        if (documentCache.isEmpty()) {
            cacheDocuments(markdownLoader.loadMarkdownsFlat());
        }
        return documentCache;
    }

    /** The Qdrant collection alias to use for retrieval queries. */
    public String qdrantSearchAlias() {
        return qdrantAlias;
    }

    /** Exposed for HybridSearchService to lazy-load cache using the Spring-injected MarkdownLoader. */
    public List<Document> loadMarkdownsForCache() {
        return markdownLoader.loadMarkdownsFlat();
    }

    // ======================== Helpers ========================

    private String chunkId(Document doc) {
        Object value = doc.getMetadata().get("chunkId");
        return value == null ? null : String.valueOf(value);
    }

    private record EsReindexResult(String physicalIndex, int documentCount) {}

    /**
     * Batch embed documents using a single API call.
     * Falls back to individual embedding if batch call fails.
     */
    private Map<String, float[]> batchEmbed(List<Document> documents) {
        Map<String, float[]> result = new LinkedHashMap<>();
        if (documents.isEmpty()) return result;

        try {
            List<String> texts = documents.stream().map(Document::getText).toList();
            org.springframework.ai.embedding.EmbeddingRequest request =
                    new org.springframework.ai.embedding.EmbeddingRequest(texts, null);
            org.springframework.ai.embedding.EmbeddingResponse response = embeddingModel.call(request);
            List<org.springframework.ai.embedding.Embedding> embeddings = response.getResults();

            for (int i = 0; i < documents.size() && i < embeddings.size(); i++) {
                String cid = chunkId(documents.get(i));
                float[] vector = embeddings.get(i).getOutput();
                result.put(cid, vector);
            }
            log.info("Batch embedded {} documents in one API call", result.size());
        } catch (Exception e) {
            log.warn("Batch embedding failed, falling back to individual embedding: {}", e.getMessage());
            for (Document doc : documents) {
                try {
                    String cid = chunkId(doc);
                    result.put(cid, embeddingModel.embed(doc.getText()));
                } catch (Exception ex) {
                    log.warn("Individual embedding failed for chunk {}", chunkId(doc));
                }
            }
        }
        return result;
    }
}
