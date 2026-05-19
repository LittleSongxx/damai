package org.javaup.ai.assistant.skill.knowledge;

import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.service.AdvancedQueryService;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class KnowledgeRetrievalPlanner {

    public static final int RETRIEVAL_TOP_K = 8;
    public static final int EVIDENCE_SOURCE_LIMIT = 6;
    public static final int EVIDENCE_SNIPPET_LIMIT = 260;
    public static final int EVIDENCE_CONTEXT_CHAR_BUDGET = 4000;

    private final AdvancedQueryService advancedQueryService;

    public KnowledgeRetrievalPlanner(AdvancedQueryService advancedQueryService) {
        this.advancedQueryService = advancedQueryService;
    }

    public KnowledgeRetrievalPlan plan(String query) {
        String normalizedQuery = normalize(query);
        List<String> subQuestions;
        if (isComplex(normalizedQuery)) {
            try {
                subQuestions = advancedQueryService.decomposeSubQuestions(normalizedQuery);
                log.info("First-pass sub-question decomposition: '{}' -> {} sub-questions",
                        normalizedQuery, subQuestions.size());
            } catch (Exception e) {
                log.warn("Sub-question decomposition failed in first pass, using single query", e);
                subQuestions = List.of(normalizedQuery);
            }
        } else {
            subQuestions = List.of(normalizedQuery);
        }
        return new KnowledgeRetrievalPlan(
                query,
                normalizedQuery,
                RETRIEVAL_TOP_K,
                true,
                EVIDENCE_SOURCE_LIMIT,
                EVIDENCE_SNIPPET_LIMIT,
                EVIDENCE_CONTEXT_CHAR_BUDGET,
                subQuestions
        );
    }

    /**
     * LLM-driven corrective query reformulation.
     */
    public String llmCorrectiveQuery(String rewrittenQuery, String normalizedQuery, String missingInfo) {
        String missing = missingInfo != null && !missingInfo.isBlank() ? missingInfo : "关键细节";
        try {
            String prompt = String.format("""
                    之前的检索关键词是 "%s"。检索时缺少以下信息：%s。
                    请生成一个更精准的检索查询词，用于补充检索缺少的信息。
                    只输出查询词本身，不要加任何前缀或解释，一行输出。
                    """, rewrittenQuery, missing);

            String result = advancedQueryService.generateHypotheticalDocument(prompt);
            if (result != null && !result.isBlank()) {
                return result.trim();
            }
        } catch (Exception e) {
            log.warn("LLM corrective query failed, using fallback", e);
        }
        return rewrittenQuery;
    }

    private boolean isComplex(String query) {
        if (query == null) return false;
        if (query.length() > 30) return true;
        String[] conjunctions = {"和", "与", "以及", "而且", "或者", "还是", "并且", "还有", "同时", "另外"};
        for (String conj : conjunctions) {
            if (query.contains(conj)) return true;
        }
        if ((query.contains("?") || query.contains("?") || query.contains("吗"))
                && query.length() > 15) return true;
        return false;
    }

    private String normalize(String query) {
        return query == null ? "" : query.trim().replaceAll("\\s+", " ");
    }
}
