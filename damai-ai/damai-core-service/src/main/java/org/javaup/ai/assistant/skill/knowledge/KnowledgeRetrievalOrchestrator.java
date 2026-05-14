package org.javaup.ai.assistant.skill.knowledge;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.assistant.runtime.AssistantStageTraceService;
import org.javaup.ai.service.AdvancedQueryService;
import org.javaup.ai.service.HybridSearchService;
import org.javaup.ai.vo.RagSearchResultVo;
import org.javaup.ai.vo.RagSourceVo;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 完整版 CRAG（Corrective RAG）编排器。
 *
 * 流程：
 * 1. 首轮检索（LLM Query Rewrite + Multi-query dense + sparse + RRF + LLM Rerank）
 * 2. 置信度评估
 * 3. 如果 LOW 置信度 → 启动纠正循环：
 *    a. Sub-question Decomposition：拆解为子问题，分别检索并合并
 *    b. HyDE：生成假想文档做第二路 dense 检索
 *    c. 合并所有纠正结果重新评估
 * 4. 选择最终答案文档
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeRetrievalOrchestrator {

    private final HybridSearchService hybridSearchService;
    private final StructuredRuleSupportService structuredRuleSupportService;
    private final KnowledgeRetrievalPlanner retrievalPlanner;
    private final KnowledgeRetrievalEvaluator retrievalEvaluator;
    private final AdvancedQueryService advancedQueryService;
    private final KnowledgeRetrievalTraceService retrievalTraceService;
    private final AssistantStageTraceService stageTraceService;

    public KnowledgeRetrievalContext retrieve(String message) {
        KnowledgeRetrievalPlan plan = retrievalPlanner.plan(message);
        return retrieve(plan);
    }

    public KnowledgeRetrievalContext retrieve(KnowledgeRetrievalPlan plan) {
        AssistantStageTraceService.StageSpan firstPassSpan = stageTraceService.startStage(
                "KNOWLEDGE_RETRIEVAL_FIRST_PASS",
                "KnowledgeRetrieval",
                plan.normalizedQuery(),
                null,
                Map.of("topK", plan.topK(), "enableRerank", plan.enableRerank(), "subQuestions", plan.subQuestions()));
        RagSearchResultVo firstPass;
        try {
            firstPass = hybridSearchService.hybridSearchWithTrace(plan.normalizedQuery(), plan.topK(), plan.enableRerank());
            stageTraceService.complete(firstPassSpan, firstPass.getRewrittenQuery(), null, null, null, null, Map.of(
                    "denseHitCount", size(firstPass.getDenseSources()),
                    "sparseHitCount", size(firstPass.getSparseSources()),
                    "fusedHitCount", size(firstPass.getFusedSources()),
                    "finalHitCount", size(firstPass.getSources())
            ));
        } catch (Exception ex) {
            stageTraceService.fail(firstPassSpan, ex, Map.of());
            throw ex;
        }
        retrievalTraceService.saveStageTrace(
                "stage",
                "knowledge.retrieval.first_pass",
                firstPass.getRetrievalTraceId(),
                plan.normalizedQuery(),
                firstPass.getRewrittenQuery(),
                firstPass.getDenseSources(),
                firstPass.getSparseSources(),
                firstPass.getFusedSources(),
                firstPass.getSources(),
                Map.of("topK", plan.topK(), "enableRerank", plan.enableRerank()));
        StructuredRuleSupportService.SupportBundle supportBundle = structuredRuleSupportService.lookup(plan.normalizedQuery());
        KnowledgeRetrievalAssessment assessment = retrievalEvaluator.assess(firstPass, supportBundle.sources(), "none", plan);

        if ("LOW".equals(assessment.confidenceLevel()) && assessment.sources().size() < 4) {
            CragCorrectionResult correction = runCorrectiveRetrieval(firstPass, plan);
            assessment = retrievalEvaluator.assess(correction.result(), supportBundle.sources(), correction.action(), plan);
            firstPass = correction.result();
        }

        List<Document> answerDocuments = selectAnswerDocuments(firstPass.getDocuments(), supportBundle.documents(), assessment.sources());
        return new KnowledgeRetrievalContext(plan, firstPass, supportBundle, assessment, answerDocuments);
    }

    /**
     * 完整版 CRAG 纠正循环：Sub-question Decomposition + HyDE + 原始纠正查询。
     */
    private CragCorrectionResult runCorrectiveRetrieval(RagSearchResultVo firstPass, KnowledgeRetrievalPlan plan) {
        log.info("CRAG: 首轮置信度低，启动纠正检索 query='{}'", plan.normalizedQuery());
        AssistantStageTraceService.StageSpan correctionSpan = stageTraceService.startStage(
                "KNOWLEDGE_RETRIEVAL_CORRECTIVE",
                "KnowledgeRetrieval",
                plan.normalizedQuery(),
                null,
                metadata("parentTraceId", firstPass.getRetrievalTraceId()));
        List<RagSourceVo> allCorrectedSources = new ArrayList<>();
        List<Document> allCorrectedDocuments = new ArrayList<>();
        String action = "crag_corrective";
        try {
            String correctiveQuery = retrievalPlanner.correctiveQuery(firstPass.getRewrittenQuery(), plan.normalizedQuery());
            RagSearchResultVo corrected = hybridSearchService.hybridSearchWithTrace(correctiveQuery, plan.topK(), plan.enableRerank());
            retrievalTraceService.saveStageTrace(
                    "stage",
                    "knowledge.retrieval.corrective_query",
                    firstPass.getRetrievalTraceId(),
                    plan.normalizedQuery(),
                    correctiveQuery,
                    corrected.getDenseSources(),
                    corrected.getSparseSources(),
                    corrected.getFusedSources(),
                    corrected.getSources(),
                    Map.of("topK", plan.topK()));
            mergeSources(allCorrectedSources, corrected.getSources());
            mergeDocuments(allCorrectedDocuments, corrected.getDocuments());

            try {
                List<String> subQuestions = advancedQueryService.decomposeSubQuestions(plan.normalizedQuery());
                if (subQuestions.size() > 1) {
                    action = "crag_sub_question_decomposition";
                    log.info("CRAG: Sub-question Decomposition 拆解为 {} 个子问题", subQuestions.size());
                    for (String subQ : subQuestions) {
                        RagSearchResultVo subResult = hybridSearchService.hybridSearchWithTrace(subQ, Math.max(3, plan.topK() / 2), plan.enableRerank());
                        retrievalTraceService.saveStageTrace(
                                "stage",
                                "knowledge.retrieval.sub_question",
                                firstPass.getRetrievalTraceId(),
                                subQ,
                                subResult.getRewrittenQuery(),
                                subResult.getDenseSources(),
                                subResult.getSparseSources(),
                                subResult.getFusedSources(),
                                subResult.getSources(),
                                Map.of("topK", Math.max(3, plan.topK() / 2)));
                        mergeSources(allCorrectedSources, subResult.getSources());
                        mergeDocuments(allCorrectedDocuments, subResult.getDocuments());
                    }
                }
            } catch (Exception e) {
                log.warn("CRAG: Sub-question Decomposition 失败，跳过", e);
            }

            try {
                List<RagSourceVo> hydeSources = hybridSearchService.hydeSearch(plan.normalizedQuery(), plan.topK());
                if (!hydeSources.isEmpty()) {
                    action = action.contains("sub_question") ? "crag_sub_question_and_hyde" : "crag_hyde";
                    log.info("CRAG: HyDE 检索命中 {} 个文档", hydeSources.size());
                    retrievalTraceService.saveStageTrace(
                            "stage",
                            "knowledge.retrieval.hyde",
                            firstPass.getRetrievalTraceId(),
                            plan.normalizedQuery(),
                            plan.normalizedQuery(),
                            hydeSources,
                            null,
                            null,
                            hydeSources,
                            Map.of("topK", plan.topK()));
                    mergeSources(allCorrectedSources, hydeSources);
                }
            } catch (Exception e) {
                log.warn("CRAG: HyDE 检索失败，跳过", e);
            }

            RagSearchResultVo mergedResult = RagSearchResultVo.builder()
                    .originalQuery(corrected.getOriginalQuery())
                    .normalizedQuery(corrected.getNormalizedQuery())
                    .rewrittenQuery(corrected.getRewrittenQuery())
                    .retrievalTraceId(corrected.getRetrievalTraceId())
                    .denseSources(corrected.getDenseSources())
                    .sparseSources(corrected.getSparseSources())
                    .fusedSources(corrected.getFusedSources())
                    .sources(dedup(allCorrectedSources))
                    .documents(dedupDocuments(allCorrectedDocuments))
                    .build();
            retrievalTraceService.saveStageTrace(
                    "stage",
                    "knowledge.retrieval.corrective_merged",
                    firstPass.getRetrievalTraceId(),
                    plan.normalizedQuery(),
                    corrected.getRewrittenQuery(),
                    corrected.getDenseSources(),
                    corrected.getSparseSources(),
                    corrected.getFusedSources(),
                    mergedResult.getSources(),
                    Map.of("action", action, "mergedSourceCount", mergedResult.getSources().size()));
            stageTraceService.complete(correctionSpan, action, null, null, null, null, Map.of(
                    "mergedSourceCount", mergedResult.getSources().size(),
                    "action", action
            ));
            log.info("CRAG: 纠正检索完成, action={}, mergedSources={}", action, mergedResult.getSources().size());
            return new CragCorrectionResult(mergedResult, action);
        } catch (Exception ex) {
            stageTraceService.fail(correctionSpan, ex, metadata("parentTraceId", firstPass.getRetrievalTraceId()));
            throw ex;
        }
    }

    private void mergeSources(List<RagSourceVo> target, List<RagSourceVo> sources) {
        if (sources != null) {
            target.addAll(sources);
        }
    }

    private void mergeDocuments(List<Document> target, List<Document> documents) {
        if (documents != null) {
            target.addAll(documents);
        }
    }

    private List<RagSourceVo> dedup(List<RagSourceVo> sources) {
        Map<String, RagSourceVo> deduped = new LinkedHashMap<>();
        for (RagSourceVo source : sources) {
            if (source.getChunkId() != null) {
                deduped.putIfAbsent(source.getChunkId(), source);
            }
        }
        return new ArrayList<>(deduped.values());
    }

    private List<Document> dedupDocuments(List<Document> documents) {
        Map<String, Document> deduped = new LinkedHashMap<>();
        for (Document doc : documents) {
            String cid = chunkId(doc);
            if (cid != null) {
                deduped.putIfAbsent(cid, doc);
            }
        }
        return new ArrayList<>(deduped.values());
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

    private record CragCorrectionResult(RagSearchResultVo result, String action) {
    }

    private int size(List<?> values) {
        return values == null ? 0 : values.size();
    }

    private Map<String, Object> metadata(String key, Object value) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (value != null) {
            metadata.put(key, value);
        }
        return metadata;
    }
}
