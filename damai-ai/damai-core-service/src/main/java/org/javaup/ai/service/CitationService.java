package org.javaup.ai.service;

import lombok.Data;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class CitationService {

    @Data
    public static class Citation {
        private int index;
        private String chunkId;
        private String source;
        private String snippet;
        private double relevanceScore;
    }

    public List<Citation> buildCitations(List<Map<String, Object>> retrievedDocs) {
        List<Citation> citations = new ArrayList<>();
        if (retrievedDocs == null) return citations;

        for (int i = 0; i < retrievedDocs.size(); i++) {
            Map<String, Object> doc = retrievedDocs.get(i);
            Citation c = new Citation();
            c.setIndex(i + 1);
            c.setChunkId(String.valueOf(doc.getOrDefault("chunkId", "")));
            c.setSource(String.valueOf(doc.getOrDefault("source", "")));
            String content = String.valueOf(doc.getOrDefault("content", ""));
            c.setSnippet(content.length() > 200 ? content.substring(0, 200) + "..." : content);
            c.setRelevanceScore(doc.containsKey("score") ? ((Number) doc.get("score")).doubleValue() : 0.0);
            citations.add(c);
        }
        return citations;
    }

    public String appendCitationMarkers(String answer, List<Citation> citations) {
        if (answer == null || citations == null || citations.isEmpty()) return answer;
        StringBuilder sb = new StringBuilder(answer);
        sb.append("\n\n---\n**参考来源：**\n");
        for (Citation c : citations) {
            sb.append(String.format("[%d] %s (chunkId: %s, 相关度: %.2f)\n",
                    c.getIndex(), c.getSource(), c.getChunkId(), c.getRelevanceScore()));
        }
        return sb.toString();
    }
}
