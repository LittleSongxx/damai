package org.javaup.ai.assistant.skill.knowledge;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class KnowledgeRetrievalPlanner {

    public static final int RETRIEVAL_TOP_K = 8;
    public static final int EVIDENCE_SOURCE_LIMIT = 6;
    public static final int EVIDENCE_SNIPPET_LIMIT = 260;
    public static final int EVIDENCE_CONTEXT_CHAR_BUDGET = 4000;

    public KnowledgeRetrievalPlan plan(String query) {
        String normalizedQuery = normalize(query);
        return new KnowledgeRetrievalPlan(
                query,
                normalizedQuery,
                RETRIEVAL_TOP_K,
                true,
                EVIDENCE_SOURCE_LIMIT,
                EVIDENCE_SNIPPET_LIMIT,
                EVIDENCE_CONTEXT_CHAR_BUDGET,
                List.of(normalizedQuery)
        );
    }

    public String correctiveQuery(String rewrittenQuery, String normalizedQuery) {
        String[] pieces = normalizedQuery.split("[，。,？?！!、\\s]+");
        String longest = "";
        for (String piece : pieces) {
            if (piece.length() > longest.length()) {
                longest = piece;
            }
        }
        if (longest.isBlank()) {
            return rewrittenQuery;
        }
        return rewrittenQuery + " " + longest;
    }

    private String normalize(String query) {
        return query == null ? "" : query.trim().replaceAll("\\s+", " ");
    }
}
