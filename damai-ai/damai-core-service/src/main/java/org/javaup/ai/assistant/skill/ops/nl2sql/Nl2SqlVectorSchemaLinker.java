package org.javaup.ai.assistant.skill.ops.nl2sql;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class Nl2SqlVectorSchemaLinker {

    private final EmbeddingModel embeddingModel;

    private final Map<String, float[]> tableEmbeddings = new HashMap<>();
    private final Map<String, String> tableDescriptions = new HashMap<>();

    public void indexSchemas(Map<String, String> tableToDescription) {
        for (Map.Entry<String, String> entry : tableToDescription.entrySet()) {
            String tableName = entry.getKey();
            String desc = entry.getValue();
            tableDescriptions.put(tableName, desc);
            try {
                float[] embedding = embeddingModel.embed(desc);
                tableEmbeddings.put(tableName, embedding);
            } catch (Exception e) {
                log.warn("Failed to embed schema for table {}: {}", tableName, e.getMessage());
            }
        }
        log.info("Indexed {} table schemas for NL2SQL vector linking", tableEmbeddings.size());
    }

    public List<String> findRelevantTables(String question, int topK) {
        if (tableEmbeddings.isEmpty()) {
            return List.of();
        }
        try {
            float[] queryEmb = embeddingModel.embed(question);
            return tableEmbeddings.entrySet().stream()
                    .map(e -> Map.entry(e.getKey(), cosineSimilarity(queryEmb, e.getValue())))
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .limit(topK)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Vector schema linking failed: {}", e.getMessage());
            return List.of();
        }
    }

    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) return 0.0;
        double dotProduct = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0 : dotProduct / denom;
    }
}
