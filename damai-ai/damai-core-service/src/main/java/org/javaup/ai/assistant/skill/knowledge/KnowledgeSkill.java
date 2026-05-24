package org.javaup.ai.assistant.skill.knowledge;

import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.assistant.AssistantEventTypes;
import org.javaup.ai.assistant.AssistantRouteType;
import org.javaup.ai.assistant.AssistantRunService;
import org.javaup.ai.assistant.AssistantSkill;
import org.javaup.ai.assistant.AssistantSkillContext;
import org.javaup.ai.assistant.AssistantSkillDescriptor;
import org.javaup.ai.assistant.AssistantSkillRiskLevel;
import org.javaup.ai.assistant.AssistantSkillResult;
import org.javaup.ai.assistant.budget.TokenBudget;
import org.javaup.ai.assistant.budget.TokenBudgetManager;
import org.javaup.ai.assistant.memory.AssistantMemoryKeyService;
import org.javaup.ai.assistant.runtime.AssistantObservedChatService;
import org.javaup.ai.assistant.tool.AssistantToolInvoker;
import org.javaup.ai.entity.AiRetrieval;
import org.javaup.ai.entity.AiRun;
import org.javaup.ai.mapper.AiRunMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
public class KnowledgeSkill implements AssistantSkill {

    private final ChatClient unifiedKnowledgeChatClient;
    private final KnowledgeRetrievalPlanner retrievalPlanner;
    private final KnowledgeRetrievalOrchestrator retrievalOrchestrator;
    private final KnowledgePromptAssemblyService promptAssemblyService;
    private final AssistantRunService assistantRunService;
    private final AssistantMemoryKeyService memoryKeyService;
    private final AssistantToolInvoker toolInvoker;
    private final KnowledgeShadowRoutingService shadowRoutingService;
    private final KnowledgeRetrievalTraceService retrievalTraceService;
    private final AssistantObservedChatService observedChatService;
    private final TokenBudgetManager tokenBudgetManager;
    private final AiRunMapper runMapper;

    public KnowledgeSkill(@Qualifier("unifiedKnowledgeChatClient") ChatClient unifiedKnowledgeChatClient,
                          KnowledgeRetrievalPlanner retrievalPlanner,
                          KnowledgeRetrievalOrchestrator retrievalOrchestrator,
                          KnowledgePromptAssemblyService promptAssemblyService,
                          AssistantRunService assistantRunService,
                          AssistantMemoryKeyService memoryKeyService,
                          AssistantToolInvoker toolInvoker,
                          KnowledgeShadowRoutingService shadowRoutingService,
                          KnowledgeRetrievalTraceService retrievalTraceService,
                          AssistantObservedChatService observedChatService,
                          TokenBudgetManager tokenBudgetManager,
                          AiRunMapper runMapper) {
        this.unifiedKnowledgeChatClient = unifiedKnowledgeChatClient;
        this.retrievalPlanner = retrievalPlanner;
        this.retrievalOrchestrator = retrievalOrchestrator;
        this.promptAssemblyService = promptAssemblyService;
        this.assistantRunService = assistantRunService;
        this.memoryKeyService = memoryKeyService;
        this.toolInvoker = toolInvoker;
        this.shadowRoutingService = shadowRoutingService;
        this.retrievalTraceService = retrievalTraceService;
        this.observedChatService = observedChatService;
        this.tokenBudgetManager = tokenBudgetManager;
        this.runMapper = runMapper;
    }

    @Override
    public AssistantRouteType routeType() {
        return AssistantRouteType.KNOWLEDGE;
    }

    @Override
    public AssistantSkillDescriptor descriptor() {
        return AssistantSkillDescriptor.builder()
                .skillId("knowledge.policy.qa")
                .name("规则知识问答")
                .description("基于闭域规则库回答退票、入场、实名、购票限制等平台规则问题。")
                .version("1.0.0")
                .goal("基于闭域 FAQ 和结构化规则提示回答大麦规则问题。")
                .instructions("必须先检索证据；证据不足时拒绝给确定答案。")
                .routeType(AssistantRouteType.KNOWLEDGE)
                .category("knowledge")
                .triggerKeywords(List.of("规则", "退票", "入场", "实名", "改签", "发票", "售后", "能退吗"))
                .toolAllowlist(List.of("knowledge.retrieve"))
                .examples(List.of("退票多久到账", "儿童票入场需要什么证件"))
                .evalCases(List.of("低置信度检索必须拒绝确定结论"))
                .inputSchemaJson("""
                        {"type":"object","required":["message"],"properties":{"message":{"type":"string"}}}
                        """)
                .outputSchemaJson("""
                        {"type":"object","required":["message"],"properties":{"message":{"type":"string"}}}
                        """)
                .riskLevel(AssistantSkillRiskLevel.LOW)
                .requiresAdmin(false)
                .requiresApproval(false)
                .enabled(true)
                .executorType("java")
                .frontendSelectable(true)
                .modelSelectable(true)
                .primarySkill(true)
                .build();
    }

    @Override
    public AssistantSkillResult execute(AssistantSkillContext context) {
        KnowledgeRetrievalPlan plan = retrievalPlanner.plan(context.getMessage());
        KnowledgeShadowRouteResult shadowRoute = shadowRoutingService.shadowRoute(plan.normalizedQuery());
        retrievalTraceService.saveStageTrace(
                "route",
                "knowledge.shadow_route",
                null,
                plan.normalizedQuery(),
                null,
                null,
                null,
                null,
                null,
                Map.of("shadowRoute", shadowRoute)
        );
        assistantRunService.appendEvent(context.getRun().getRunId(), AssistantEventTypes.KNOWLEDGE_ROUTE_SHADOWED, Map.of(
                "runId", context.getRun().getRunId(),
                "query", plan.normalizedQuery(),
                "mode", shadowRoute.mode(),
                "scopeCandidates", shadowRoute.scopeCandidates(),
                "topicCandidates", shadowRoute.topicCandidates(),
                "documentCandidates", shadowRoute.documentCandidates()
        ));
        assistantRunService.appendEvent(context.getRun().getRunId(), AssistantEventTypes.RETRIEVAL_STARTED, Map.of(
                "runId", context.getRun().getRunId(),
                "query", context.getMessage(),
                "normalizedQuery", plan.normalizedQuery(),
                "topK", plan.topK(),
                "enableRerank", plan.enableRerank(),
                "subQuestions", plan.subQuestions(),
                "shadowRoute", shadowRoute
        ));

        KnowledgeRetrievalContext retrievalContext = toolInvoker.invoke(context.getRun().getRunId(), "knowledge.retrieve", "rag", plan, () ->
                retrievalOrchestrator.retrieve(plan));
        KnowledgeRetrievalAssessment assessment = retrievalContext.assessment();

        AiRetrieval retrieval = new AiRetrieval();
        retrieval.setRunId(context.getRun().getRunId());
        retrieval.setConversationId(context.getRun().getConversationId());
        retrieval.setUserId(context.getRun().getUserId());
        retrieval.setOriginalQuery(context.getMessage());
        retrieval.setNormalizedQuery(plan.normalizedQuery());
        retrieval.setRewrittenQuery(retrievalContext.searchResult().getRewrittenQuery());
        retrieval.setDenseHitsJson(JSON.toJSONString(retrievalContext.searchResult().getDenseSources()));
        retrieval.setSparseHitsJson(JSON.toJSONString(retrievalContext.searchResult().getSparseSources()));
        retrieval.setFusedHitsJson(JSON.toJSONString(retrievalContext.searchResult().getFusedSources()));
        retrieval.setFinalHitsJson(JSON.toJSONString(assessment.sources()));
        retrieval.setConfidenceScore(assessment.confidenceScore());
        retrieval.setConfidenceLevel(assessment.confidenceLevel());
        retrieval.setCorrectiveAction(assessment.correctiveAction());
        retrieval.setRetrievalPlanJson(JSON.toJSONString(buildRetrievalPlanPayload(retrievalContext, shadowRoute)));
        assistantRunService.saveRetrieval(retrieval);

        Map<String, Object> retrievalCompletedPayload = new LinkedHashMap<>();
        retrievalCompletedPayload.put("runId", context.getRun().getRunId());
        retrievalCompletedPayload.put("retrievalId", retrieval.getRetrievalId());
        retrievalCompletedPayload.put("normalizedQuery", plan.normalizedQuery());
        retrievalCompletedPayload.put("rewrittenQuery", retrievalContext.searchResult().getRewrittenQuery());
        retrievalCompletedPayload.put("confidenceScore", assessment.confidenceScore());
        retrievalCompletedPayload.put("confidenceLevel", assessment.confidenceLevel());
        retrievalCompletedPayload.put("correctiveAction", assessment.correctiveAction());
        retrievalCompletedPayload.put("budget", Map.of(
                "evidenceSourceLimit", plan.evidenceSourceLimit(),
                "evidenceSnippetLimit", plan.evidenceSnippetLimit(),
                "evidenceContextCharBudget", plan.evidenceContextCharBudget()
        ));
        retrievalCompletedPayload.put("usedChannels", usedChannels(retrievalContext));
        retrievalCompletedPayload.put("subQuestions", plan.subQuestions());
        retrievalCompletedPayload.put("omittedEvidenceCount", Math.max(0, rawSourceCount(retrievalContext) - assessment.sources().size()));
        retrievalCompletedPayload.put("evidenceSourceLimit", plan.evidenceSourceLimit());
        retrievalCompletedPayload.put("selectedSourceCount", assessment.sources().size());
        retrievalCompletedPayload.put("denseHitCount", retrievalContext.searchResult().getDenseSources() == null ? 0 : retrievalContext.searchResult().getDenseSources().size());
        retrievalCompletedPayload.put("sparseHitCount", retrievalContext.searchResult().getSparseSources() == null ? 0 : retrievalContext.searchResult().getSparseSources().size());
        retrievalCompletedPayload.put("sources", assessment.sources());
        retrievalCompletedPayload.put("shadowRoute", shadowRoute);
        assistantRunService.appendEvent(context.getRun().getRunId(), AssistantEventTypes.RETRIEVAL_COMPLETED, retrievalCompletedPayload);

        // CRAG three-way + Self-RAG: refuse to answer if not answerable
        if ("NOT_ANSWERABLE".equals(assessment.answerabilityLevel())
                || "INCORRECT".equals(assessment.confidenceLevel())
                || (assessment.hasContradictions() && assessment.sources().size() < 3)) {
            String answer = "我已经检索了当前的闭域规则库，但这轮命中的证据不够扎实或存在冲突，暂时不能直接给出确定结论。请补充具体场景、节目或关键词，我再基于规则继续检索。";
            // Escalation: detect consecutive refusal rounds and suggest human takeover
            if (shouldSuggestEscalation(context)) {
                answer += "\n\n您已多次遇到检索无结果的情况。建议您转人工客服获得更直接的帮助。";
                assistantRunService.appendEvent(context.getRun().getRunId(),
                        "ESCALATION_SUGGESTED",
                        Map.of("reason", "consecutive_refusal",
                                "conversationId", context.getRun().getConversationId()));
            }
            log.warn("Self-RAG: refusing to answer due to {} evidence, hasContradictions={}, missingInfo={}",
                    assessment.answerabilityLevel(), assessment.hasContradictions(), assessment.missingInfo());
            return AssistantSkillResult.builder()
                    .message(answer)
                    .responseSummary(answer)
                    .retrieval(retrieval)
                    .sourceRefs(List.of())
                    .build();
        }

        TokenBudget tokenBudget = tokenBudgetManager.createBudget("qwen3.6-plus");
        String userPrompt = context.buildUserPrompt();
        tokenBudget.recordUsage("MEMORY", tokenBudgetManager.estimateTokens(context.formatMemorySummary()));
        tokenBudget.recordUsage("USER_PROFILE", tokenBudgetManager.estimateTokens(context.formatUserProfile()));
        KnowledgePromptAssemblyResult prompt = promptAssemblyService.assemble(userPrompt, retrievalContext, tokenBudget);
        tokenBudgetManager.verifyBudget(tokenBudget);
        Flux<String> tokenStream = observedChatService.stream(
                unifiedKnowledgeChatClient,
                "KNOWLEDGE_ANSWER",
                "KnowledgeAnswer",
                "qwen3.6-plus",
                memoryKeyService.userConversationKey(context.getRun().getUserId(), context.getRun().getConversationId()),
                prompt.groundedPrompt()
        );

        return AssistantSkillResult.builder()
                .messageStream(tokenStream)
                .retrieval(retrieval)
                .evidenceChunks(retrievalContext.answerDocuments().stream()
                        .map(document -> document == null ? null : document.getText())
                        .filter(text -> text != null && !text.isBlank())
                        .collect(Collectors.toList()))
                .sourceRefs(prompt.sourceRefList())
                .build();
    }

    private Map<String, Object> buildRetrievalPlanPayload(KnowledgeRetrievalContext retrievalContext,
                                                          KnowledgeShadowRouteResult shadowRoute) {
        KnowledgeRetrievalPlan plan = retrievalContext.plan();
        return Map.of(
                "topK", plan.topK(),
                "enableRerank", plan.enableRerank(),
                "evidenceSourceLimit", plan.evidenceSourceLimit(),
                "evidenceSnippetLimit", plan.evidenceSnippetLimit(),
                "evidenceContextCharBudget", plan.evidenceContextCharBudget(),
                "subQuestions", plan.subQuestions(),
                "usedStructuredSupport", !retrievalContext.supportBundle().sources().isEmpty(),
                "shadowRoute", shadowRoute
        );
    }

    private List<String> usedChannels(KnowledgeRetrievalContext retrievalContext) {
        List<String> channels = new ArrayList<>();
        if (retrievalContext.searchResult().getDenseSources() != null && !retrievalContext.searchResult().getDenseSources().isEmpty()) {
            channels.add("dense");
        }
        if (retrievalContext.searchResult().getSparseSources() != null && !retrievalContext.searchResult().getSparseSources().isEmpty()) {
            channels.add("sparse");
        }
        if (!retrievalContext.supportBundle().sources().isEmpty()) {
            channels.add("structured_rule");
        }
        return channels;
    }

    private int rawSourceCount(KnowledgeRetrievalContext retrievalContext) {
        int count = retrievalContext.searchResult().getSources() == null ? 0 : retrievalContext.searchResult().getSources().size();
        count += retrievalContext.supportBundle().sources().size();
        return count;
    }

    /**
     * Checks recent runs in the same conversation for consecutive refusal/fallback patterns.
     * Suggests escalation when ≥2 consecutive runs ended without a successful answer.
     */
    private boolean shouldSuggestEscalation(AssistantSkillContext context) {
        if (context.getRun() == null || context.getRun().getConversationId() == null) return false;
        var wrapper = new LambdaQueryWrapper<AiRun>()
                .eq(AiRun::getConversationId, context.getRun().getConversationId())
                .eq(AiRun::getStatus, 1)
                .orderByDesc(AiRun::getCreateTime)
                .last("LIMIT 5");
        List<AiRun> recent = runMapper.selectList(wrapper);
        if (recent.size() < 2) return false;
        // Count consecutive runs that ended in either CLARIFICATION mode or with a refusal response
        int consecutiveFailures = 0;
        for (AiRun r : recent) {
            if (r.getRunId().equals(context.getRun().getRunId())) continue;
            String status = r.getRunStatus();
            if (status != null && (status.contains("CLARIFICATION") || status.contains("REFUSED"))) {
                consecutiveFailures++;
            } else {
                break; // only count consecutive
            }
        }
        return consecutiveFailures >= 2;
    }

}
