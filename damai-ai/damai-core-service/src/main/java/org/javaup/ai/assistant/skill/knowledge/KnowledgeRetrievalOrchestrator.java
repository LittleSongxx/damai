package org.javaup.ai.assistant.skill.knowledge;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.assistant.runtime.AssistantStageTraceService;
import org.javaup.ai.rag.RagRetrievalFacade;
import org.javaup.ai.service.AdvancedQueryService;
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
 * 增强版 CRAG（Corrective RAG）编排器。
 *
 * 首轮检索：LLM Query Rewrite + Multi-query dense + HyDE dense + sparse + RRF + Rerank
 * 子问题拆解：复杂查询在首轮即拆解子问题并行检索
 * 多维度评估：LLM as Judge 做语义相关性 + 覆盖度 + 冲突检测，CRAG 三路分类
 * 纠正循环：CORRECT→知识精炼, AMBIGUOUS→扩展Top-k, INCORRECT→LLM查询改写
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeRetrievalOrchestrator {

    private final StructuredRuleSupportService structuredRuleSupportService;
    private final KnowledgeRetrievalPlanner retrievalPlanner;
    private final KnowledgeRetrievalEvaluator retrievalEvaluator;
    private final AdvancedQueryService advancedQueryService;
    private final RagRetrievalFacade retrievalFacade;
    private final KnowledgeRetrievalTraceService retrievalTraceService;
    private final AssistantStageTraceService stageTraceService;

    public KnowledgeRetrievalContext retrieve(String message) {
        KnowledgeRetrievalPlan plan = retrievalPlanner.plan(message);
        return retrieve(plan);
    }

    public KnowledgeRetrievalContext retrieve(KnowledgeRetrievalPlan plan) {
        if (plan.complexity() == KnowledgeRetrievalPlan.Complexity.SIMPLE) {
            return retrieveSimple(plan);
        }
        return retrieveFull(plan);
    }

    private KnowledgeRetrievalContext retrieveSimple(KnowledgeRetrievalPlan plan) {
        RagSearchResultVo result = engineRetrieveSimple(plan.normalizedQuery(), plan.topK());
        var supportBundle = structuredRuleSupportService.lookup(plan.normalizedQuery());
        List<RagSourceVo> merged = new ArrayList<>();
        if (result.getSources() != null) merged.addAll(result.getSources());
        if (supportBundle.sources() != null) merged.addAll(supportBundle.sources());
        var deduped = dedup(merged);
        List<Document> answerDocs = selectAnswerDocuments(
                result.getDocuments(), supportBundle.documents(), deduped);
        KnowledgeRetrievalAssessment assessment = new KnowledgeRetrievalAssessment(
                0.75, "CORRECT", "simple", deduped,
                "HIGH", "HIGH", false, "ANSWERABLE", "", List.of());
        return new KnowledgeRetrievalContext(plan, result, supportBundle, assessment, answerDocs);
    }

    private RagSearchResultVo engineRetrieveSimple(String query, int topK) {
        return retrievalFacade.retrieveSimple(query, topK);
    }

    private KnowledgeRetrievalContext retrieveFull(KnowledgeRetrievalPlan plan) {
        var firstPassSpan = stageTraceService.startStage(
                "KNOWLEDGE_RETRIEVAL_FIRST_PASS",
                "KnowledgeRetrieval",
                plan.normalizedQuery(),
                null,
                Map.of("topK", plan.topK(), "enableRerank", plan.enableRerank(),
                        "subQuestions", plan.subQuestions()));
        RagSearchResultVo firstPass;
        try {
            firstPass = engineRetrieve(plan.normalizedQuery(), plan.topK(), plan.enableRerank());
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
        retrievalTraceService.saveStageTrace("stage", "knowledge.retrieval.first_pass",
                firstPass.getRetrievalTraceId(), plan.normalizedQuery(), firstPass.getRewrittenQuery(),
                firstPass.getDenseSources(), firstPass.getSparseSources(),
                firstPass.getFusedSources(), firstPass.getSources(),
                retrievalMetadata(firstPass, "topK", plan.topK(), "enableRerank", plan.enableRerank()));

        // Sub-question decomposition: search per sub-question in first pass for complex queries
        if (plan.subQuestions().size() > 1) {
            log.info("First-pass sub-question decomposition: {} sub-questions", plan.subQuestions().size());
            List<RagSourceVo> allSubSources = new ArrayList<>(firstPass.getSources());
            List<Document> allSubDocuments = new ArrayList<>(firstPass.getDocuments());
            for (String subQ : plan.subQuestions()) {
                if (subQ.equals(plan.normalizedQuery())) continue;
                var subResult = engineRetrieve(subQ, Math.max(4, plan.topK() / 2), plan.enableRerank());
                retrievalTraceService.saveStageTrace("stage", "knowledge.retrieval.sub_question_first_pass",
                        firstPass.getRetrievalTraceId(), subQ, subResult.getRewrittenQuery(),
                        subResult.getDenseSources(), subResult.getSparseSources(),
                        subResult.getFusedSources(), subResult.getSources(),
                        retrievalMetadata(subResult, "topK", Math.max(4, plan.topK() / 2)));
                mergeSources(allSubSources, subResult.getSources());
                mergeDocuments(allSubDocuments, subResult.getDocuments());
            }
            RagSearchResultVo mergedPass = RagSearchResultVo.builder()
                    .originalQuery(firstPass.getOriginalQuery())
                    .normalizedQuery(firstPass.getNormalizedQuery())
                    .rewrittenQuery(firstPass.getRewrittenQuery())
                    .retrievalTraceId(firstPass.getRetrievalTraceId())
                    .denseSources(firstPass.getDenseSources())
                    .sparseSources(firstPass.getSparseSources())
                    .fusedSources(firstPass.getFusedSources())
                    .sources(dedup(allSubSources))
                    .documents(dedupDocuments(allSubDocuments))
                    .build();
            firstPass = mergedPass;
        }

        var supportBundle = structuredRuleSupportService.lookup(plan.normalizedQuery());
        KnowledgeRetrievalAssessment assessment = retrievalEvaluator.assess(
                firstPass, supportBundle.sources(), "none", plan);

        // CRAG three-way corrective routing
        switch (assessment.confidenceLevel()) {
            case "CORRECT":
                log.info("CRAG: CORRECT confidence, applying lightweight refinement");
                firstPass = refineEvidence(firstPass, plan.evidenceSourceLimit(), plan.evidenceContextCharBudget());
                assessment = retrievalEvaluator.assess(
                        firstPass, supportBundle.sources(), "crag_correct_refined", plan);
                break;
            case "AMBIGUOUS": {
                log.info("CRAG: AMBIGUOUS confidence, expanding retrieval");
                var correction = runAmbiguousRetrieval(firstPass, plan);
                assessment = retrievalEvaluator.assess(
                        correction.result(), supportBundle.sources(), correction.action(), plan);
                firstPass = correction.result();
                break;
            }
            case "INCORRECT": {
                log.info("CRAG: INCORRECT confidence, full query reformulation");
                var correction = runIncorrectRetrieval(firstPass, plan, assessment.missingInfo());
                assessment = retrievalEvaluator.assess(
                        correction.result(), supportBundle.sources(), correction.action(), plan);
                firstPass = correction.result();
                break;
            }
            default:
                // Legacy path: treat LOW/other as corrective trigger
                if ("LOW".equals(assessment.confidenceLevel()) && assessment.sources().size() < 4) {
                    var correction = runAmbiguousRetrieval(firstPass, plan);
                    assessment = retrievalEvaluator.assess(
                            correction.result(), supportBundle.sources(), correction.action(), plan);
                    firstPass = correction.result();
                }
        }

        List<Document> answerDocuments = selectAnswerDocuments(
                firstPass.getDocuments(), supportBundle.documents(), assessment.sources());
        return new KnowledgeRetrievalContext(plan, firstPass, supportBundle, assessment, answerDocuments);
    }

    /**
     * AMBIGUOUS: Expand retrieval with 3x Top-k + sub-question decomposition.
     */
    private CragCorrectionResult runAmbiguousRetrieval(RagSearchResultVo firstPass, KnowledgeRetrievalPlan plan) {
        int expandedTopK = plan.topK() * 3;
        log.info("CRAG AMBIGUOUS: expanding topK from {} to {}", plan.topK(), expandedTopK);

        var correctionSpan = stageTraceService.startStage("KNOWLEDGE_RETRIEVAL_AMBIGUOUS",
                "KnowledgeRetrieval", plan.normalizedQuery(), null,
                metadata("parentTraceId", firstPass.getRetrievalTraceId()));
        List<RagSourceVo> allSources = new ArrayList<>();
        List<Document> allDocuments = new ArrayList<>();

        try {
            RagSearchResultVo expanded = engineRetrieve(
                    plan.normalizedQuery(), expandedTopK, plan.enableRerank());
            mergeSources(allSources, expanded.getSources());
            mergeDocuments(allDocuments, expanded.getDocuments());

            try {
                List<String> subQuestions = advancedQueryService.decomposeSubQuestions(plan.normalizedQuery());
                if (subQuestions.size() > 1) {
                    for (String subQ : subQuestions) {
                        RagSearchResultVo subResult = engineRetrieve(
                                subQ, Math.max(4, expandedTopK / 2), plan.enableRerank());
                        mergeSources(allSources, subResult.getSources());
                        mergeDocuments(allDocuments, subResult.getDocuments());
                    }
                }
            } catch (Exception e) {
                log.warn("Sub-question decomposition failed in AMBIGUOUS corrective", e);
            }

            stageTraceService.complete(correctionSpan, "crag_ambiguous", null, null, null, null,
                    Map.of("mergedSourceCount", allSources.size()));
        } catch (Exception ex) {
            stageTraceService.fail(correctionSpan, ex, Map.of());
            throw ex;
        }

        return new CragCorrectionResult(RagSearchResultVo.builder()
                .originalQuery(firstPass.getOriginalQuery())
                .normalizedQuery(firstPass.getNormalizedQuery())
                .rewrittenQuery(firstPass.getRewrittenQuery())
                .retrievalTraceId(firstPass.getRetrievalTraceId())
                .sources(dedup(allSources))
                .documents(dedupDocuments(allDocuments))
                .build(), "crag_ambiguous");
    }

    /**
     * INCORRECT: LLM-driven query reformulation + 2x Top-k + sub-question decomposition.
     */
    private CragCorrectionResult runIncorrectRetrieval(RagSearchResultVo firstPass,
                                                        KnowledgeRetrievalPlan plan,
                                                        String missingInfo) {
        String reformulatedQuery = retrievalPlanner.llmCorrectiveQuery(
                firstPass.getRewrittenQuery(), plan.normalizedQuery(), missingInfo);
        log.info("CRAG INCORRECT: reformulated query '{}' -> '{}'",
                plan.normalizedQuery(), reformulatedQuery);

        var correctionSpan = stageTraceService.startStage("KNOWLEDGE_RETRIEVAL_INCORRECT",
                "KnowledgeRetrieval", plan.normalizedQuery(), null,
                metadata("parentTraceId", firstPass.getRetrievalTraceId()));
        List<RagSourceVo> allSources = new ArrayList<>();
        List<Document> allDocuments = new ArrayList<>();

        try {
            RagSearchResultVo corrected = engineRetrieve(
                    reformulatedQuery, plan.topK() * 2, plan.enableRerank());
            mergeSources(allSources, corrected.getSources());
            mergeDocuments(allDocuments, corrected.getDocuments());

            try {
                List<String> subQuestions = advancedQueryService.decomposeSubQuestions(reformulatedQuery);
                for (String subQ : subQuestions) {
                    if (subQ.equals(reformulatedQuery)) continue;
                    RagSearchResultVo subResult = engineRetrieve(
                            subQ, Math.max(4, plan.topK()), plan.enableRerank());
                    mergeSources(allSources, subResult.getSources());
                    mergeDocuments(allDocuments, subResult.getDocuments());
                }
            } catch (Exception e) {
                log.warn("Sub-question decomposition failed in INCORRECT corrective", e);
            }

            stageTraceService.complete(correctionSpan, "crag_incorrect", null, null, null, null,
                    Map.of("reformulatedQuery", reformulatedQuery,
                            "missingInfo", missingInfo != null ? missingInfo : "",
                            "mergedSourceCount", allSources.size()));
        } catch (Exception ex) {
            stageTraceService.fail(correctionSpan, ex, Map.of());
            throw ex;
        }

        return new CragCorrectionResult(RagSearchResultVo.builder()
                .originalQuery(firstPass.getOriginalQuery())
                .normalizedQuery(firstPass.getNormalizedQuery())
                .rewrittenQuery(reformulatedQuery)
                .retrievalTraceId(firstPass.getRetrievalTraceId())
                .sources(dedup(allSources))
                .documents(dedupDocuments(allDocuments))
                .build(), "crag_incorrect");
    }

    /**
     * Lightweight evidence refinement: deduplicate near-identical chunks, sort by score,
     * trim to evidence budget. Called on CORRECT path to reduce noise before answer generation.
     */
    private RagSearchResultVo refineEvidence(RagSearchResultVo result, int sourceLimit, int charBudget) {
        List<RagSourceVo> sources = result.getSources();
        if (sources == null || sources.size() <= 1) return result;

        // Sort by score descending
        List<RagSourceVo> sorted = new ArrayList<>(sources);
        sorted.sort((a, b) -> Double.compare(
                b.getScore() != null ? b.getScore() : 0D,
                a.getScore() != null ? a.getScore() : 0D));

        // Deduplicate by content overlap: if chunk A's text is >75% contained in chunk B, drop A
        List<RagSourceVo> refined = new ArrayList<>();
        for (RagSourceVo candidate : sorted) {
            if (refined.size() >= sourceLimit) break;
            boolean isDup = false;
            String cSnippet = candidate.getSnippet();
            if (cSnippet != null && cSnippet.length() > 50) {
                for (RagSourceVo kept : refined) {
                    String kSnippet = kept.getSnippet();
                    if (kSnippet != null && kSnippet.length() > 50
                            && textOverlapRatio(cSnippet, kSnippet) > 0.75) {
                        isDup = true;
                        break;
                    }
                }
            }
            if (!isDup) refined.add(candidate);
        }

        // Trim snippets to char budget
        int budgetLeft = charBudget;
        List<RagSourceVo> trimmed = new ArrayList<>();
        for (RagSourceVo source : refined) {
            if (budgetLeft <= 0) break;
            String snippet = source.getSnippet();
            if (snippet != null && snippet.length() > budgetLeft) {
                source = RagSourceVo.builder()
                        .chunkId(source.getChunkId())
                        .title(source.getTitle())
                        .source(source.getSource())
                        .section(source.getSection())
                        .snippet(snippet.substring(0, budgetLeft))
                        .score(source.getScore())
                        .parentBlockId(source.getParentBlockId())
                        .build();
                budgetLeft = 0;
            } else {
                budgetLeft -= snippet != null ? snippet.length() : 0;
            }
            trimmed.add(source);
        }

        log.info("CRAG CORRECT refinement: {} -> {} sources (budget={} chars)",
                sources.size(), trimmed.size(), charBudget);
        return RagSearchResultVo.builder()
                .originalQuery(result.getOriginalQuery())
                .normalizedQuery(result.getNormalizedQuery())
                .rewrittenQuery(result.getRewrittenQuery())
                .retrievalTraceId(result.getRetrievalTraceId())
                .denseSources(result.getDenseSources())
                .sparseSources(result.getSparseSources())
                .fusedSources(result.getFusedSources())
                .sources(trimmed)
                .documents(result.getDocuments())
                .build();
    }

    /** Jaccard-like character trigram overlap ratio for near-duplicate detection. */
    private double textOverlapRatio(String a, String b) {
        if (a == null || b == null) return 0;
        String shorter = a.length() <= b.length() ? a : b;
        String longer = a.length() <= b.length() ? b : a;
        if (shorter.length() < 20) return 0;

        int matchChars = 0;
        int step = 20;
        for (int i = 0; i + step <= shorter.length(); i += step) {
            String segment = shorter.substring(i, i + step);
            if (longer.contains(segment)) matchChars += step;
        }
        return (double) matchChars / shorter.length();
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

    private List<Document> selectAnswerDocuments(List<Document> retrievedDocuments,
                                                  List<Document> supportDocuments,
                                                  List<RagSourceVo> selectedSources) {
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

    private RagSearchResultVo engineRetrieve(String query, int topK, boolean enableRerank) {
        return retrievalFacade.retrieve(query, topK, enableRerank);
    }

    private RagSearchResultVo withResolvedDocuments(RagSearchResultVo result) {
        return result;
    }

    private record CragCorrectionResult(RagSearchResultVo result, String action) {}

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

    private Map<String, Object> retrievalMetadata(RagSearchResultVo result, Object... extraPairs) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (result != null && result.getMetadata() != null) {
            metadata.putAll(result.getMetadata());
        }
        for (int index = 0; index + 1 < extraPairs.length; index += 2) {
            metadata.put(String.valueOf(extraPairs[index]), extraPairs[index + 1]);
        }
        return metadata;
    }
}
