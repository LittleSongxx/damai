package org.javaup.ai.assistant.skill.knowledge;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.assistant.runtime.AssistantStageTraceService;
import org.javaup.ai.rag.RagRetrievalFacade;
import org.javaup.ai.rag.RetrievalStrategy;
import org.javaup.ai.rag.RetrievalStrategyPolicy;
import org.javaup.ai.rag.RetrievalStrategyProfile;
import org.javaup.ai.rag.channel.KnowledgeRetrievalFilter;
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
 * Customer-service retrieval orchestrator.
 * <p>
 * First pass is strategy-driven instead of a hard-coded simple/full split.
 * Corrective retrieval must change the retrieval method (missing-slot rewrite,
 * step-back query, structured support, and optional HyDE) rather than repeating
 * the same search with a larger topK.
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
    private final RetrievalStrategyPolicy strategyPolicy;
    private final CorrectiveQueryService correctiveQueryService;

    public KnowledgeRetrievalContext retrieve(String message) {
        KnowledgeRetrievalPlan plan = retrievalPlanner.plan(message);
        return retrieve(plan);
    }

    public KnowledgeRetrievalContext retrieve(KnowledgeRetrievalPlan plan) {
        return retrieve(plan, KnowledgeRetrievalFilter.empty());
    }

    public KnowledgeRetrievalContext retrieve(KnowledgeRetrievalPlan plan, KnowledgeRetrievalFilter filter) {
        RetrievalStrategy strategy = strategyPolicy.firstPass(plan, filter);
        if (strategy.profile() == RetrievalStrategyProfile.HANDOFF_OR_CLARIFY) {
            return handoffContext(plan, strategy);
        }
        if (strategy.profile() == RetrievalStrategyProfile.FAST_EXACT) {
            return retrieveFastExact(plan, filter, strategy);
        }
        return retrieveFull(plan, filter, strategy);
    }

    private KnowledgeRetrievalContext handoffContext(KnowledgeRetrievalPlan plan, RetrievalStrategy strategy) {
        RagSearchResultVo empty = RagSearchResultVo.builder()
                .originalQuery(plan.originalQuery())
                .normalizedQuery(plan.normalizedQuery())
                .rewrittenQuery(plan.normalizedQuery())
                .sources(List.of())
                .documents(List.of())
                .metadata(Map.of(
                        "strategyProfile", strategy.profile().name(),
                        "strategyReason", strategy.reason()
                ))
                .build();
        KnowledgeRetrievalAssessment assessment = new KnowledgeRetrievalAssessment(
                0D, "INCORRECT", "handoff_or_clarify", List.of(),
                "LOW", "LOW", false, "NOT_ANSWERABLE",
                "问题缺少可安全检索的条件，建议澄清或转人工", List.of());
        return new KnowledgeRetrievalContext(plan, empty,
                new StructuredRuleSupportService.SupportBundle(List.of(), List.of()), assessment, List.of());
    }

    private KnowledgeRetrievalContext retrieveFastExact(KnowledgeRetrievalPlan plan, KnowledgeRetrievalFilter filter,
                                                     RetrievalStrategy strategy) {
        RagSearchResultVo result = engineRetrieve(plan.normalizedQuery(), strategy, filter);
        var supportBundle = structuredRuleSupportService.lookup(plan.normalizedQuery());
        List<RagSourceVo> merged = new ArrayList<>();
        if (result.getSources() != null) merged.addAll(result.getSources());
        if (supportBundle.sources() != null) merged.addAll(supportBundle.sources());
        var deduped = dedup(merged);
        List<Document> answerDocs = selectAnswerDocuments(
                result.getDocuments(), supportBundle.documents(), deduped);
        RagSearchResultVo assessedResult = RagSearchResultVo.builder()
                .originalQuery(result.getOriginalQuery())
                .normalizedQuery(result.getNormalizedQuery())
                .rewrittenQuery(result.getRewrittenQuery())
                .retrievalTraceId(result.getRetrievalTraceId())
                .denseSources(result.getDenseSources())
                .sparseSources(result.getSparseSources())
                .fusedSources(result.getFusedSources())
                .sources(deduped)
                .documents(answerDocs)
                .metadata(result.getMetadata())
                .build();
        KnowledgeRetrievalAssessment assessment = fastExactAssessment(assessedResult, supportBundle, plan);
        return new KnowledgeRetrievalContext(plan, assessedResult, supportBundle, assessment, answerDocs);
    }

    private KnowledgeRetrievalAssessment fastExactAssessment(RagSearchResultVo result,
                                                          StructuredRuleSupportService.SupportBundle supportBundle,
                                                          KnowledgeRetrievalPlan plan) {
        if (result.getSources() == null || result.getSources().isEmpty()) {
            return new KnowledgeRetrievalAssessment(
                    0D, "INCORRECT", "fast_exact_no_evidence", List.of(),
                    "LOW", "LOW", false, "NOT_ANSWERABLE",
                    "没有命中可用于回答的证据", List.of());
        }
        try {
            return retrievalEvaluator.assess(result, supportBundle.sources(), "fast_exact", plan);
        } catch (RuntimeException ex) {
            log.warn("fast exact retrieval assessment failed, using conservative fallback", ex);
            double score = Math.min(0.55D, 0.25D + result.getSources().size() * 0.08D);
            String level = result.getSources().size() >= Math.min(3, plan.topK()) ? "AMBIGUOUS" : "INCORRECT";
            String coverage = result.getSources().size() >= Math.min(3, plan.topK()) ? "MEDIUM" : "LOW";
            String answerability = "AMBIGUOUS".equals(level) ? "PARTIALLY_ANSWERABLE" : "NOT_ANSWERABLE";
            return new KnowledgeRetrievalAssessment(
                    score, level, "fast_exact_fallback", result.getSources(),
                    "MEDIUM", coverage, false, answerability, "检索评估失败，已降级为保守判断", List.of());
        }
    }

    private KnowledgeRetrievalContext retrieveFull(KnowledgeRetrievalPlan plan, KnowledgeRetrievalFilter filter,
                                                   RetrievalStrategy strategy) {
        var firstPassSpan = stageTraceService.startStage(
                "KNOWLEDGE_RETRIEVAL_FIRST_PASS",
                "KnowledgeRetrieval",
                plan.normalizedQuery(),
                null,
                Map.of("topK", strategy.topK(), "enableRerank", strategy.enableRerank(),
                        "subQuestions", plan.subQuestions(),
                        "strategyProfile", strategy.profile().name(),
                        "enabledChannels", strategy.enabledChannels()));
        RagSearchResultVo firstPass;
        try {
            firstPass = engineRetrieve(plan.normalizedQuery(), strategy, filter);
            stageTraceService.complete(firstPassSpan, firstPass.getRewrittenQuery(), null, null, null, null, Map.of(
                    "denseHitCount", size(firstPass.getDenseSources()),
                    "sparseHitCount", size(firstPass.getSparseSources()),
                    "fusedHitCount", size(firstPass.getFusedSources()),
                    "finalHitCount", size(firstPass.getSources()),
                    "strategyProfile", strategy.profile().name()
            ));
        } catch (Exception ex) {
            stageTraceService.fail(firstPassSpan, ex, Map.of());
            throw ex;
        }
        retrievalTraceService.saveStageTrace("stage", "knowledge.retrieval.first_pass",
                firstPass.getRetrievalTraceId(), plan.normalizedQuery(), firstPass.getRewrittenQuery(),
                firstPass.getDenseSources(), firstPass.getSparseSources(),
                firstPass.getFusedSources(), firstPass.getSources(),
                retrievalMetadata(firstPass, "topK", strategy.topK(),
                        "enableRerank", strategy.enableRerank(),
                        "strategyProfile", strategy.profile().name(),
                        "enabledChannels", strategy.enabledChannels()));

        // Sub-question decomposition: search per sub-question in first pass for complex queries
        if (plan.subQuestions().size() > 1) {
            log.info("First-pass sub-question decomposition: {} sub-questions", plan.subQuestions().size());
            List<RagSourceVo> allSubSources = new ArrayList<>(firstPass.getSources());
            List<Document> allSubDocuments = new ArrayList<>(firstPass.getDocuments());
            for (String subQ : plan.subQuestions()) {
                if (subQ.equals(plan.normalizedQuery())) continue;
                RetrievalStrategy subStrategy = RetrievalStrategy.standardHybrid(
                        Math.max(4, strategy.topK() / 2), strategy.enableRerank(), strategy.highRisk(),
                        "first-pass sub-question retrieval");
                var subResult = engineRetrieve(subQ, subStrategy, filter);
                retrievalTraceService.saveStageTrace("stage", "knowledge.retrieval.sub_question_first_pass",
                        firstPass.getRetrievalTraceId(), subQ, subResult.getRewrittenQuery(),
                        subResult.getDenseSources(), subResult.getSparseSources(),
                        subResult.getFusedSources(), subResult.getSources(),
                        retrievalMetadata(subResult, "topK", subStrategy.topK(),
                                "strategyProfile", subStrategy.profile().name()));
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
                var correction = runAmbiguousRetrieval(firstPass, plan, assessment.missingInfo(), filter);
                KnowledgeRetrievalAssessment corrected = retrievalEvaluator.assess(
                        correction.result(), supportBundle.sources(), correction.action(), plan);
                CorrectionDecision decision = chooseCorrection(firstPass, assessment, correction.result(), corrected, correction.action());
                firstPass = decision.result();
                assessment = decision.assessment();
                break;
            }
            case "INCORRECT": {
                log.info("CRAG: INCORRECT confidence, running step-back and reformulated retrieval");
                var correction = runIncorrectRetrieval(firstPass, plan, assessment.missingInfo(), filter);
                KnowledgeRetrievalAssessment corrected = retrievalEvaluator.assess(
                        correction.result(), supportBundle.sources(), correction.action(), plan);
                CorrectionDecision decision = chooseCorrection(firstPass, assessment, correction.result(), corrected, correction.action());
                firstPass = decision.result();
                assessment = decision.assessment();
                break;
            }
            default:
                // Treat LOW/other as corrective trigger.
                if ("LOW".equals(assessment.confidenceLevel()) && assessment.sources().size() < 4) {
                    var correction = runAmbiguousRetrieval(firstPass, plan, assessment.missingInfo(), filter);
                    KnowledgeRetrievalAssessment corrected = retrievalEvaluator.assess(
                            correction.result(), supportBundle.sources(), correction.action(), plan);
                    CorrectionDecision decision = chooseCorrection(firstPass, assessment, correction.result(), corrected, correction.action());
                    firstPass = decision.result();
                    assessment = decision.assessment();
                }
        }

        List<Document> answerDocuments = selectAnswerDocuments(
                firstPass.getDocuments(), supportBundle.documents(), assessment.sources());
        return new KnowledgeRetrievalContext(plan, firstPass, supportBundle, assessment, answerDocuments);
    }

    /**
     * AMBIGUOUS: Use missing-slot rewrite and topic-constrained retrieval.
     */
    private CragCorrectionResult runAmbiguousRetrieval(RagSearchResultVo firstPass, KnowledgeRetrievalPlan plan,
                                                       String missingInfo,
                                                       KnowledgeRetrievalFilter filter) {
        RetrievalStrategy strategy = strategyPolicy.corrective(plan, "AMBIGUOUS", missingInfo, filter);
        if (strategy.profile() == RetrievalStrategyProfile.HANDOFF_OR_CLARIFY) {
            return new CragCorrectionResult(firstPass, "crag_ambiguous_handoff");
        }
        CorrectiveQueryService.CorrectiveQueryPlan correctivePlan =
                correctiveQueryService.planAmbiguous(plan.normalizedQuery(), missingInfo);
        log.info("CRAG AMBIGUOUS: missing-slot query='{}'", correctivePlan.query());

        var correctionSpan = stageTraceService.startStage("KNOWLEDGE_RETRIEVAL_AMBIGUOUS",
                "KnowledgeRetrieval", plan.normalizedQuery(), null,
                metadata("parentTraceId", firstPass.getRetrievalTraceId(),
                        "strategyProfile", strategy.profile().name(),
                        "correctiveQuery", correctivePlan.query()));
        List<RagSourceVo> allSources = new ArrayList<>(safeSources(firstPass));
        List<Document> allDocuments = new ArrayList<>(safeDocuments(firstPass));

        try {
            RagSearchResultVo rewritten = engineRetrieve(correctivePlan.query(), strategy, filter);
            mergeSources(allSources, rewritten.getSources());
            mergeDocuments(allDocuments, rewritten.getDocuments());

            try {
                List<String> subQuestions = advancedQueryService.decomposeSubQuestions(correctivePlan.query());
                if (subQuestions.size() > 1) {
                    for (String subQ : subQuestions) {
                        RetrievalStrategy subStrategy = RetrievalStrategy.standardHybrid(
                                Math.max(4, plan.topK()), true, strategy.highRisk(),
                                "ambiguous corrective sub-question");
                        RagSearchResultVo subResult = engineRetrieve(subQ, subStrategy, filter);
                        mergeSources(allSources, subResult.getSources());
                        mergeDocuments(allDocuments, subResult.getDocuments());
                    }
                }
            } catch (Exception e) {
                log.warn("Sub-question decomposition failed in AMBIGUOUS corrective", e);
            }

            stageTraceService.complete(correctionSpan, "crag_ambiguous", null, null, null, null,
                    Map.of("mergedSourceCount", allSources.size(),
                            "correctiveQuery", correctivePlan.query(),
                            "strategyProfile", strategy.profile().name()));
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
                .metadata(Map.of("correctionDecision", correctivePlan.reason(),
                        "strategyProfile", strategy.profile().name()))
                .build(), "crag_ambiguous_missing_slot");
    }

    /**
     * INCORRECT: step-back + reformulated query + optional HyDE recovery.
     */
    private CragCorrectionResult runIncorrectRetrieval(RagSearchResultVo firstPass,
                                                        KnowledgeRetrievalPlan plan,
                                                        String missingInfo,
                                                        KnowledgeRetrievalFilter filter) {
        RetrievalStrategy strategy = strategyPolicy.corrective(plan, "INCORRECT", missingInfo, filter);
        if (strategy.profile() == RetrievalStrategyProfile.HANDOFF_OR_CLARIFY) {
            return new CragCorrectionResult(firstPass, "crag_incorrect_handoff");
        }
        CorrectiveQueryService.CorrectiveQueryPlan correctivePlan =
                correctiveQueryService.planIncorrect(plan.normalizedQuery(), missingInfo);
        String reformulatedQuery = correctivePlan.query();
        log.info("CRAG INCORRECT: reformulated query '{}' -> '{}'",
                plan.normalizedQuery(), reformulatedQuery);

        var correctionSpan = stageTraceService.startStage("KNOWLEDGE_RETRIEVAL_INCORRECT",
                "KnowledgeRetrieval", plan.normalizedQuery(), null,
                metadata("parentTraceId", firstPass.getRetrievalTraceId(),
                        "strategyProfile", strategy.profile().name(),
                        "stepBackQuery", correctivePlan.stepBackQuery(),
                        "reformulatedQuery", reformulatedQuery));
        List<RagSourceVo> allSources = new ArrayList<>(safeSources(firstPass));
        List<Document> allDocuments = new ArrayList<>(safeDocuments(firstPass));

        try {
            RagSearchResultVo corrected = engineRetrieve(reformulatedQuery, strategy, filter);
            mergeSources(allSources, corrected.getSources());
            mergeDocuments(allDocuments, corrected.getDocuments());

            if (correctivePlan.stepBackQuery() != null && !correctivePlan.stepBackQuery().isBlank()) {
                RetrievalStrategy stepBackStrategy = RetrievalStrategy.standardHybrid(
                        Math.max(4, plan.topK()), true, strategy.highRisk(),
                        "incorrect corrective step-back retrieval");
                RagSearchResultVo stepBack = engineRetrieve(correctivePlan.stepBackQuery(), stepBackStrategy, filter);
                mergeSources(allSources, stepBack.getSources());
                mergeDocuments(allDocuments, stepBack.getDocuments());
            }

            try {
                List<String> subQuestions = advancedQueryService.decomposeSubQuestions(reformulatedQuery);
                for (String subQ : subQuestions) {
                    if (subQ.equals(reformulatedQuery)) continue;
                    RetrievalStrategy subStrategy = RetrievalStrategy.standardHybrid(
                            Math.max(4, plan.topK()), true, strategy.highRisk(),
                            "incorrect corrective sub-question");
                    RagSearchResultVo subResult = engineRetrieve(subQ, subStrategy, filter);
                    mergeSources(allSources, subResult.getSources());
                    mergeDocuments(allDocuments, subResult.getDocuments());
                }
            } catch (Exception e) {
                log.warn("Sub-question decomposition failed in INCORRECT corrective", e);
            }

            stageTraceService.complete(correctionSpan, "crag_incorrect", null, null, null, null,
                    Map.of("reformulatedQuery", reformulatedQuery,
                            "stepBackQuery", correctivePlan.stepBackQuery(),
                            "strategyProfile", strategy.profile().name(),
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
                .metadata(Map.of("correctionDecision", correctivePlan.reason(),
                        "strategyProfile", strategy.profile().name(),
                        "stepBackQuery", correctivePlan.stepBackQuery()))
                .build(), "crag_incorrect_step_back");
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
                        .channelName(source.getChannelName())
                        .validUntil(source.getValidUntil())
                        .version(source.getVersion())
                        .scope(source.getScope())
                        .topic(source.getTopic())
                        .documentId(source.getDocumentId())
                        .audience(source.getAudience())
                        .region(source.getRegion())
                        .docStatus(source.getDocStatus())
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

    private RagSearchResultVo engineRetrieve(String query, RetrievalStrategy strategy, KnowledgeRetrievalFilter filter) {
        return retrievalFacade.retrieve(query, strategy, filter == null ? KnowledgeRetrievalFilter.empty() : filter);
    }

    private CorrectionDecision chooseCorrection(RagSearchResultVo originalResult,
                                                KnowledgeRetrievalAssessment originalAssessment,
                                                RagSearchResultVo correctedResult,
                                                KnowledgeRetrievalAssessment correctedAssessment,
                                                String correctiveAction) {
        double originalScore = assessmentScore(originalAssessment);
        double correctedScore = assessmentScore(correctedAssessment);
        boolean better = correctedScore > originalScore + 0.05D
                || ("NOT_ANSWERABLE".equals(originalAssessment.answerabilityLevel())
                && !"NOT_ANSWERABLE".equals(correctedAssessment.answerabilityLevel()));
        if (better) {
            return new CorrectionDecision(correctedResult, correctedAssessment, correctiveAction, correctedScore - originalScore);
        }
        KnowledgeRetrievalAssessment kept = new KnowledgeRetrievalAssessment(
                originalAssessment.confidenceScore(),
                originalAssessment.confidenceLevel(),
                correctiveAction + "_kept_first_pass",
                originalAssessment.sources(),
                originalAssessment.relevanceLevel(),
                originalAssessment.coverageLevel(),
                originalAssessment.hasContradictions(),
                originalAssessment.answerabilityLevel(),
                originalAssessment.missingInfo(),
                originalAssessment.verifiedClaims());
        return new CorrectionDecision(originalResult, kept, correctiveAction + "_kept_first_pass", correctedScore - originalScore);
    }

    private double assessmentScore(KnowledgeRetrievalAssessment assessment) {
        if (assessment == null) return 0D;
        double score = assessment.confidenceScore() == null ? 0D : assessment.confidenceScore();
        score += "ANSWERABLE".equals(assessment.answerabilityLevel()) ? 0.35D : 0D;
        score += "PARTIALLY_ANSWERABLE".equals(assessment.answerabilityLevel()) ? 0.15D : 0D;
        score += "HIGH".equals(assessment.coverageLevel()) ? 0.20D : ("MEDIUM".equals(assessment.coverageLevel()) ? 0.08D : 0D);
        score += "HIGH".equals(assessment.relevanceLevel()) ? 0.20D : ("MEDIUM".equals(assessment.relevanceLevel()) ? 0.08D : 0D);
        if (assessment.hasContradictions()) score -= 0.4D;
        return score;
    }

    private List<RagSourceVo> safeSources(RagSearchResultVo result) {
        return result == null || result.getSources() == null ? List.of() : result.getSources();
    }

    private List<Document> safeDocuments(RagSearchResultVo result) {
        return result == null || result.getDocuments() == null ? List.of() : result.getDocuments();
    }

    private record CragCorrectionResult(RagSearchResultVo result, String action) {}
    private record CorrectionDecision(RagSearchResultVo result, KnowledgeRetrievalAssessment assessment,
                                      String action, double delta) {}

    private int size(List<?> values) {
        return values == null ? 0 : values.size();
    }

    private Map<String, Object> metadata(Object... pairs) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            Object value = pairs[index + 1];
            if (value != null) {
                metadata.put(String.valueOf(pairs[index]), value);
            }
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
