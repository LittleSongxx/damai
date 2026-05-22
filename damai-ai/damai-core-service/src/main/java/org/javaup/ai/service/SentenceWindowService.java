package org.javaup.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.entity.RagChunk;
import org.javaup.ai.mapper.RagChunkMapper;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sentence Window Retrieval: expands each retrieved chunk to include its
 * neighboring chunks (prev/next siblings within the same parent block),
 * providing richer context while maintaining precise embedding retrieval.
 *
 * <p>The technique:
 * <ol>
 *   <li>Retrieve small chunks by embedding similarity (precise)</li>
 *   <li>Expand each hit to include prev/next sibling chunks (rich context)</li>
 *   <li>Merge overlapping windows to avoid duplication</li>
 * </ol>
 *
 * <p>Relies on the parent-child chunk structure established during ingestion
 * (prevChunkId / nextChunkId / parentChunkId in d_ai_rag_chunk).
 */
@Slf4j
@Service
public class SentenceWindowService {

    private final RagChunkMapper chunkMapper;

    @Value("${damai.ai.retrieval.sentence-window.enabled:true}")
    private boolean enabled;

    @Value("${damai.ai.retrieval.sentence-window.window-size:1}")
    private int windowSize;

    public SentenceWindowService(RagChunkMapper chunkMapper) {
        this.chunkMapper = chunkMapper;
    }

    /**
     * Expand retrieved documents by including neighboring chunks within the
     * same parent block. Deduplicates and merges overlapping windows.
     */
    public List<Document> expand(List<Document> documents) {
        if (!enabled || documents == null || documents.isEmpty() || windowSize <= 0) {
            return documents != null ? documents : List.of();
        }
        try {
            return expandWindows(documents);
        } catch (Exception e) {
            log.warn("Sentence window expansion failed, returning original documents: {}", e.getMessage());
            return documents;
        }
    }

    private List<Document> expandWindows(List<Document> documents) {
        Map<String, Document> expanded = new LinkedHashMap<>();

        for (Document doc : documents) {
            String chunkId = doc.getMetadata() != null
                    ? String.valueOf(doc.getMetadata().getOrDefault("chunkId", ""))
                    : "";
            if (chunkId.isEmpty()) {
                expanded.putIfAbsent(doc.getId() != null ? doc.getId() : chunkId, doc);
                continue;
            }

            RagChunk centerChunk = chunkMapper.selectByChunkUid(chunkId);
            if (centerChunk == null || centerChunk.getParentChunkId() == null) {
                expanded.putIfAbsent(chunkId, doc);
                continue;
            }

            // Fetch all sibling chunks in the same parent block
            List<RagChunk> siblings = chunkMapper.selectByParentChunkId(centerChunk.getParentChunkId());
            if (siblings.isEmpty()) {
                expanded.putIfAbsent(chunkId, doc);
                continue;
            }

            // Find the center chunk's position and expand the window
            int centerIdx = -1;
            for (int i = 0; i < siblings.size(); i++) {
                if (chunkId.equals(siblings.get(i).getChunkUid())) {
                    centerIdx = i;
                    break;
                }
            }
            if (centerIdx < 0) {
                expanded.putIfAbsent(chunkId, doc);
                continue;
            }

            // Include chunks within windowSize of the center
            int start = Math.max(0, centerIdx - windowSize);
            int end = Math.min(siblings.size(), centerIdx + windowSize + 1);

            StringBuilder windowText = new StringBuilder();
            for (int i = start; i < end; i++) {
                RagChunk sibling = siblings.get(i);
                if (StringUtils.hasText(sibling.getText())) {
                    if (windowText.length() > 0) {
                        windowText.append("\n");
                    }
                    windowText.append(sibling.getText());
                }
                // Track all chunk IDs in this window
                String sid = sibling.getChunkUid();
                if (!expanded.containsKey(sid)) {
                    Map<String, Object> meta = new LinkedHashMap<>(doc.getMetadata());
                    meta.put("chunkId", sid);
                    meta.put("windowExpanded", true);
                    meta.put("windowCenter", chunkId);
                    expanded.put(sid, new Document(sibling.getText() != null ? sibling.getText() : "", meta));
                }
            }
        }

        int beforeSize = documents.size();
        int afterSize = expanded.size();
        if (afterSize > beforeSize) {
            log.info("Sentence window: {} docs expanded to {} (window={})", beforeSize, afterSize, windowSize);
        }
        return new ArrayList<>(expanded.values());
    }
}
