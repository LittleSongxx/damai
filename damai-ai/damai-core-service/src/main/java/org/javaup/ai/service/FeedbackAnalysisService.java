package org.javaup.ai.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.config.AiSecurityProperties;
import org.javaup.ai.entity.AiFeedback;
import org.javaup.ai.entity.AiRun;
import org.javaup.ai.entity.FaqEntry;
import org.javaup.ai.entity.FeedbackAnalysis;
import org.javaup.ai.mapper.AiFeedbackMapper;
import org.javaup.ai.mapper.AiRunMapper;
import org.javaup.ai.mapper.FaqEntryMapper;
import org.javaup.ai.mapper.FeedbackAnalysisMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 反馈闭环服务 - 参考 Dify 的 annotation 和反馈学习设计。
 * 自动分析用户反馈，聚类问题，生成知识库改进建议。
 */
@Slf4j
@Service
public class FeedbackAnalysisService {

    private final AiFeedbackMapper feedbackMapper;
    private final AiRunMapper runMapper;
    private final FeedbackAnalysisMapper analysisMapper;
    private final ChatClient chatClient;
    private final NotificationService notificationService;
    private final AiSecurityProperties securityProperties;
    private final FaqEntryMapper faqEntryMapper;

    public FeedbackAnalysisService(AiFeedbackMapper feedbackMapper,
                                    AiRunMapper runMapper,
                                    FeedbackAnalysisMapper analysisMapper,
                                    @Qualifier("unifiedGeneralChatClient") ChatClient chatClient,
                                    NotificationService notificationService,
                                    AiSecurityProperties securityProperties,
                                    FaqEntryMapper faqEntryMapper) {
        this.feedbackMapper = feedbackMapper;
        this.runMapper = runMapper;
        this.analysisMapper = analysisMapper;
        this.chatClient = chatClient;
        this.notificationService = notificationService;
        this.securityProperties = securityProperties;
        this.faqEntryMapper = faqEntryMapper;
    }

    /**
     * 提交反馈后触发自动分析
     */
    @Transactional
    public void analyzeFeedback(AiFeedback feedback) {
        if (!"down".equals(feedback.getRating())) return;

        AiRun run = runMapper.selectOne(
                Wrappers.lambdaQuery(AiRun.class)
                        .eq(AiRun::getRunId, feedback.getRunId()));

        if (run == null) return;

        // 使用LLM分析负面反馈的原因
        FeedbackAnalysisResult analysis = analyzeWithLLM(run, feedback);

        // 查找同类问题聚类
        String clusterKey = generateClusterKey(analysis.issueCategory, run.getRouteType());

        // 计算同类问题数量
        int affectedCount = countSimilarIssues(clusterKey);

        FeedbackAnalysis fa = new FeedbackAnalysis();
        fa.setAnalysisId(UUID.randomUUID().toString().replace("-", ""));
        fa.setFeedbackId(feedback.getFeedbackId());
        fa.setRunId(feedback.getRunId());
        fa.setAnalysisType(analysis.analysisType);
        fa.setIssueSummary(analysis.issueSummary);
        fa.setSuggestedAction(analysis.suggestedAction);
        fa.setActionStatus("PENDING");
        fa.setClusterKey(clusterKey);
        fa.setAffectedFeedbackCount(affectedCount);
        fa.setCreateTime(new Date());
        fa.setEditTime(new Date());
        fa.setStatus(1);
        analysisMapper.insert(fa);

        log.info("Feedback analysis created: analysisId={}, clusterKey={}, affectedCount={}",
                fa.getAnalysisId(), clusterKey, affectedCount);

        // 如果同类问题积累到阈值，自动生成知识库补充建议
        if (affectedCount >= 5) {
            triggerKnowledgeGapAlert(fa);
        }
    }

    /**
     * LLM分析反馈根因
     */
    private FeedbackAnalysisResult analyzeWithLLM(AiRun run, AiFeedback feedback) {
        try {
            String prompt = """
                    分析以下AI客服的负面反馈，判断问题类型并给出改进建议。返回JSON:
                    {
                      "analysisType": "BAD_CASE_CLUSTER/KNOWLEDGE_GAP/SENTIMENT_DRIFT/MODEL_HALLUCINATION/TOOL_ERROR",
                      "issueCategory": "简短分类如:退款规则错误/实体识别失败/上下文丢失",
                      "issueSummary": "不超过100字的问题摘要",
                      "suggestedAction": "具体改进建议"
                    }

                    用户问题: %s
                    AI回答: %s
                    用户反馈: %s (评分: %s)
                    路由类型: %s

                    只输出JSON。
                    """.formatted(
                    truncate(run.getUserMessage(), 500),
                    truncate(run.getResponseSummary(), 500),
                    truncate(feedback.getComment(), 300),
                    feedback.getRating(),
                    run.getRouteType());

            String result = chatClient.prompt().user(prompt).call().content();
            if (StringUtils.hasText(result)) {
                result = result.trim().replaceAll("```\\w*\\n?", "").replaceAll("```", "").trim();
                var json = JSON.parseObject(result);
                return new FeedbackAnalysisResult(
                        json.getString("analysisType") != null ? json.getString("analysisType") : "BAD_CASE_CLUSTER",
                        json.getString("issueCategory") != null ? json.getString("issueCategory") : "未分类",
                        json.getString("issueSummary") != null ? json.getString("issueSummary") : "",
                        json.getString("suggestedAction") != null ? json.getString("suggestedAction") : ""
                );
            }
        } catch (Exception e) {
            log.warn("Feedback analysis LLM call failed: {}", e.getMessage());
        }
        return new FeedbackAnalysisResult("BAD_CASE_CLUSTER", "未分类",
                "用户不满意AI回答", "人工审核该回答");
    }

    private String generateClusterKey(String issueCategory, String routeType) {
        return (issueCategory != null ? issueCategory : "unknown") + ":" + (routeType != null ? routeType : "unknown");
    }

    private int countSimilarIssues(String clusterKey) {
        Long count = analysisMapper.selectCount(
                Wrappers.lambdaQuery(FeedbackAnalysis.class)
                        .eq(FeedbackAnalysis::getClusterKey, clusterKey));
        return count != null ? count.intValue() : 0;
    }

    /**
     * 知识缺口告警 - 当同类问题积累到阈值时触发，自动生成知识库补充建议并通知管理员。
     */
    private void triggerKnowledgeGapAlert(FeedbackAnalysis analysis) {
        log.warn("KNOWLEDGE_GAP_ALERT: clusterKey={}, affectedCount={}, suggestion={}",
                analysis.getClusterKey(), analysis.getAffectedFeedbackCount(), analysis.getSuggestedAction());

        if (notificationService == null || securityProperties == null) return;

        String kbDraft = generateKbDraft(analysis);
        String adminUserIds = securityProperties.getAdminUserIds();
        if (!StringUtils.hasText(adminUserIds)) return;

        String title = String.format("知识缺口警报: %s（%d条同类问题）",
                analysis.getIssueSummary(), analysis.getAffectedFeedbackCount());
        String content = String.format(
                "系统检测到知识缺口：%s\n\n改进建议：%s\n\nLLM生成的FAQ草稿：\n%s\n\n前往知识库管理页面补充此问题。",
                analysis.getIssueSummary(),
                analysis.getSuggestedAction(),
                kbDraft);

        for (String adminIdStr : adminUserIds.split(",")) {
            try {
                Long adminUserId = Long.parseLong(adminIdStr.trim());
                notificationService.createNotification(adminUserId, "KNOWLEDGE_GAP",
                        title, content, "/admin/knowledge/documents?action=create",
                        "补充知识", "in_app");
            } catch (NumberFormatException ignored) {
                // Skip invalid admin user IDs
            }
        }

        // Auto-save FAQ draft for admin review when KNOWLEDGE_GAP detected
        if ("KNOWLEDGE_GAP".equals(analysis.getAnalysisType())) {
            try {
                FaqEntry entry = new FaqEntry();
                entry.setFaqId("fb-gap-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
                entry.setQuestion(analysis.getIssueSummary());
                entry.setAnswer(kbDraft);
                entry.setCategory(analysis.getClusterKey());
                entry.setPriority(5);
                entry.setHitCount(0);
                entry.setEnabled(0);
                entry.setEmbeddingCached(0);
                entry.setCreateTime(new Date());
                entry.setEditTime(new Date());
                entry.setStatus(1);
                faqEntryMapper.insert(entry);
                log.info("Auto-created FAQ draft from feedback analysis: faqId={}, clusterKey={}",
                        entry.getFaqId(), analysis.getClusterKey());
            } catch (Exception e) {
                log.warn("Failed to auto-create FAQ draft from feedback: {}", e.getMessage());
            }
        }
    }

    private String generateKbDraft(FeedbackAnalysis analysis) {
        try {
            String prompt = String.format("""
                    你是一个FAQ知识库撰写助手。用户反馈了一个知识缺口，请生成一个FAQ条目。

                    问题类别：%s
                    问题摘要：%s
                    改进建议：%s

                    请生成一个完整的FAQ，包含：
                    - question: 简洁的用户问法（1-2句话）
                    - answer: 准确完整的回答（3-5句话）
                    - keywords: 3-5个关键词

                    只输出纯文本，不要JSON格式。
                    """, analysis.getIssueSummary(), analysis.getIssueSummary(), analysis.getSuggestedAction());
            String result = chatClient.prompt().user(prompt).call().content();
            return result != null ? result.trim() : analysis.getSuggestedAction();
        } catch (Exception e) {
            log.warn("KB draft generation failed: {}", e.getMessage());
            return analysis.getSuggestedAction();
        }
    }

    /**
     * 获取待处理的分析问题列表
     */
    public List<FeedbackAnalysis> getPendingAnalyses() {
        return analysisMapper.selectList(
                Wrappers.lambdaQuery(FeedbackAnalysis.class)
                        .eq(FeedbackAnalysis::getActionStatus, "PENDING")
                        .orderByDesc(FeedbackAnalysis::getAffectedFeedbackCount));
    }

    /**
     * 标记分析问题为已处理
     */
    @Transactional
    public void resolveAnalysis(String analysisId, String actionTaken, Long resolvedBy) {
        FeedbackAnalysis analysis = analysisMapper.selectOne(
                Wrappers.lambdaQuery(FeedbackAnalysis.class)
                        .eq(FeedbackAnalysis::getAnalysisId, analysisId));
        if (analysis != null) {
            analysis.setActionTaken(actionTaken);
            analysis.setActionStatus("RESOLVED");
            analysis.setResolvedBy(resolvedBy);
            analysis.setResolvedAt(new Date());
            analysis.setEditTime(new Date());
            analysisMapper.updateById(analysis);
        }
    }

    /**
     * 获取知识缺口Top-N (用于运营优化知识库)
     */
    public List<KnowledgeGap> getTopKnowledgeGaps(int limit) {
        List<FeedbackAnalysis> analyses = analysisMapper.selectList(
                Wrappers.lambdaQuery(FeedbackAnalysis.class)
                        .eq(FeedbackAnalysis::getAnalysisType, "KNOWLEDGE_GAP")
                        .eq(FeedbackAnalysis::getActionStatus, "PENDING")
                        .orderByDesc(FeedbackAnalysis::getAffectedFeedbackCount)
                        .last("limit " + limit));

        return analyses.stream()
                .map(a -> new KnowledgeGap(
                        a.getAnalysisId(),
                        a.getIssueSummary(),
                        a.getSuggestedAction(),
                        a.getAffectedFeedbackCount()))
                .collect(Collectors.toList());
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    record FeedbackAnalysisResult(String analysisType, String issueCategory,
                                   String issueSummary, String suggestedAction) {}

    public record KnowledgeGap(String analysisId, String issueSummary,
                                String suggestedAction, int affectedCount) {}
}
