package org.javaup.ai.assistant.skill.knowledge;

import lombok.RequiredArgsConstructor;
import org.javaup.ai.service.HybridSearchService;
import org.javaup.ai.vo.RagSearchResultVo;
import org.javaup.ai.vo.RagSourceVo;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class KnowledgeRetrievalOrchestrator {

    private final HybridSearchService hybridSearchService;
    private final StructuredRuleSupportService structuredRuleSupportService;
    private final KnowledgeRetrievalPlanner retrievalPlanner;
    private final KnowledgeRetrievalEvaluator retrievalEvaluator;

    public KnowledgeRetrievalContext retrieve(String message) {
        KnowledgeRetrievalPlan plan = retrievalPlanner.plan(message);
        return retrieve(plan);
    }

    public KnowledgeRetrievalContext retrieve(KnowledgeRetrievalPlan plan) {
        RagSearchResultVo firstPass = hybridSearchService.hybridSearchWithTrace(plan.normalizedQuery(), plan.topK(), plan.enableRerank());
        StructuredRuleSupportService.SupportBundle supportBundle = structuredRuleSupportService.lookup(plan.normalizedQuery());
        KnowledgeRetrievalAssessment assessment = retrievalEvaluator.assess(firstPass, supportBundle.sources(), "none", plan);
        if ("LOW".equals(assessment.confidenceLevel()) && assessment.sources().size() < 4) {
            String correctiveQuery = retrievalPlanner.correctiveQuery(firstPass.getRewrittenQuery(), plan.normalizedQuery());
            RagSearchResultVo corrected = hybridSearchService.hybridSearchWithTrace(correctiveQuery, plan.topK(), plan.enableRerank());
            assessment = retrievalEvaluator.assess(corrected, supportBundle.sources(), "query_decomposition", plan);
            firstPass = corrected;
        }
        List<Document> answerDocuments = selectAnswerDocuments(firstPass.getDocuments(), supportBundle.documents(), assessment.sources());
        return new KnowledgeRetrievalContext(plan, firstPass, supportBundle, assessment, answerDocuments);
    }

    private List<Document> selectAnswerDocuments(List<Document> retrievedDocuments, List<Document> supportDocuments, List<RagSourceVo> selectedSources) {
        Set<String> selectedIds = selectedSources.stream()
                .map(RagSourceVo::getChunkId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<Document> candidates = new ArrayList<>();
        if (retrievedDocuments != null) {
            candidates.addAll(retrievedDocuments);
        }
        if (supportDocuments != null) {
            candidates.addAll(supportDocuments);
        }
        return candidates.stream()
                .filter(document -> selectedIds.contains(chunkId(document)))
                .toList();
    }

    private String chunkId(Document document) {
        Object value = document.getMetadata().get("chunkId");
        return value == null ? null : String.valueOf(value);
    }
}
