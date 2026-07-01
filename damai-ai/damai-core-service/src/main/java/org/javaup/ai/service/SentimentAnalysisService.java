package org.javaup.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.entity.SentimentRecord;
import org.javaup.ai.mapper.SentimentRecordMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 情感分析服务 - 实时分析用户情感并触发升级策略。
 * 参考 Intercom/Zendesk AI 的情感路由设计。
 */
@Slf4j
@Service
public class SentimentAnalysisService {

    private final SentimentRecordMapper recordMapper;
    private final ChatClient chatClient;

    // 升级触发条件 — 参考行业标准
    private static final double NEGATIVE_ESCALATION_THRESHOLD = 0.7;
    private static final int NEGATIVE_STREAK_THRESHOLD = 3;
    private static final List<String> CRISIS_KEYWORDS = List.of(
            "投诉", "报警", "欺诈", "骗钱", "律师", "12315", "315", "媒体", "曝光");
    private static final List<String> NEGATIVE_KEYWORDS = List.of(
            "生气", "气死", "愤怒", "垃圾", "太差", "差劲", "糟糕", "失望", "不满",
            "骗人", "骗子", "退款不到账", "没人管", "解决不了", "崩溃", "赔偿");
    private static final List<String> POSITIVE_KEYWORDS = List.of(
            "谢谢", "感谢", "满意", "不错", "很好", "靠谱", "解决了");

    public SentimentAnalysisService(SentimentRecordMapper recordMapper,
                                     @Qualifier("unifiedGeneralChatClient") ChatClient chatClient) {
        this.recordMapper = recordMapper;
        this.chatClient = chatClient;
    }

    /**
     * 分析用户消息情感
     */
    public SentimentResult analyze(String userMessage, String runId, String conversationId, Long userId) {
        if (!StringUtils.hasText(userMessage)) {
            return new SentimentResult("NEUTRAL", 0.0, false, List.of(), false, null);
        }

        // 关键词紧急检测（毫秒级）
        boolean keywordCrisis = CRISIS_KEYWORDS.stream().anyMatch(userMessage::contains);

        // LLM情感分析
        SentimentResult result = analyzeWithLLM(userMessage);

        if (keywordCrisis && !result.isUrgent) {
            result = new SentimentResult(result.sentiment, Math.max(result.intensity, 0.8),
                    true, result.emotionTags, result.shouldEscalate, "关键词触发紧急标记");
        }

        // 检查负面连续
        boolean negativeStreak = checkNegativeStreak(userId, runId);
        if (negativeStreak && "NEGATIVE".equals(result.sentiment)) {
            result = new SentimentResult(result.sentiment, result.intensity,
                    true, result.emotionTags, true,
                    "连续" + NEGATIVE_STREAK_THRESHOLD + "轮负面情绪");
        }

        // 判断是否需要升级
        boolean shouldEscalate = result.isUrgent
                || ("NEGATIVE".equals(result.sentiment) && result.intensity >= NEGATIVE_ESCALATION_THRESHOLD)
                || keywordCrisis
                || result.shouldEscalate;

        // 保存情感记录
        saveRecord(runId, conversationId, userId, userMessage, result, shouldEscalate);

        return new SentimentResult(result.sentiment, result.intensity, result.isUrgent,
                result.emotionTags, shouldEscalate,
                shouldEscalate ? "情感升级: sentiment=" + result.sentiment + " intensity=" + result.intensity : null);
    }

    /**
     * 客服首响同步快判，只做关键词、强度和危机词检测，不调用 LLM。
     */
    public SentimentResult quickAnalyze(String userMessage, String runId, String conversationId, Long userId) {
        if (!StringUtils.hasText(userMessage)) {
            return new SentimentResult("NEUTRAL", 0.0, false, List.of(), false, null);
        }
        boolean crisis = CRISIS_KEYWORDS.stream().anyMatch(userMessage::contains);
        boolean negative = crisis || NEGATIVE_KEYWORDS.stream().anyMatch(userMessage::contains);
        boolean positive = !negative && POSITIVE_KEYWORDS.stream().anyMatch(userMessage::contains);
        double intensity = crisis ? 0.9D : (negative ? 0.72D : (positive ? 0.25D : 0.0D));
        String sentiment = negative ? "NEGATIVE" : (positive ? "POSITIVE" : "NEUTRAL");
        List<String> tags = crisis
                ? List.of("crisis_keyword", "needs_priority_support")
                : (negative ? List.of("negative_keyword") : (positive ? List.of("positive_keyword") : List.of()));
        boolean negativeStreak = negative && checkNegativeStreak(userId, runId);
        boolean shouldEscalate = crisis || intensity >= NEGATIVE_ESCALATION_THRESHOLD || negativeStreak;
        String reason = crisis
                ? "命中投诉/维权高危词"
                : (negativeStreak ? "连续" + NEGATIVE_STREAK_THRESHOLD + "轮负面情绪" : null);
        SentimentResult result = new SentimentResult(sentiment, intensity, crisis, tags, shouldEscalate, reason);
        saveRecord(runId, conversationId, userId, userMessage, result, shouldEscalate);
        return result;
    }

    /**
     * 异步深判用于补充 LLM 情绪标签，不阻塞客服首响。
     */
    public void analyzeAsync(String userMessage, String runId, String conversationId, Long userId) {
        CompletableFuture.runAsync(() -> {
            try {
                analyze(userMessage, runId, conversationId, userId);
            } catch (Exception e) {
                log.warn("Async sentiment analysis failed: {}", e.getMessage());
            }
        });
    }

    /**
     * LLM情感分析 — 使用结构化输出
     */
    private SentimentResult analyzeWithLLM(String userMessage) {
        try {
            String prompt = """
                    分析以下用户消息的情感。返回JSON:
                    {"sentiment":"POSITIVE/NEUTRAL/NEGATIVE","intensity":0.0-1.0,"isUrgent":true/false,"emotionTags":["angry","anxious","satisfied"...]}
                    消息: "%s"
                    只输出JSON。
                    """.formatted(userMessage.length() > 500 ? userMessage.substring(0, 500) : userMessage);

            String result = chatClient.prompt().user(prompt).call().content();
            if (StringUtils.hasText(result)) {
                result = result.trim().replaceAll("```\\w*\\n?", "").replaceAll("```", "").trim();
                JSONObject json = JSON.parseObject(result);
                return new SentimentResult(
                        json.getString("sentiment") != null ? json.getString("sentiment") : "NEUTRAL",
                        json.getDouble("intensity") != null ? json.getDouble("intensity") : 0.5,
                        json.getBoolean("isUrgent") != null && json.getBoolean("isUrgent"),
                        json.getList("emotionTags", String.class) != null ? json.getList("emotionTags", String.class) : List.of(),
                        false, null
                );
            }
        } catch (Exception e) {
            log.warn("Sentiment analysis failed: {}", e.getMessage());
        }
        return new SentimentResult("NEUTRAL", 0.0, false, List.of(), false, null);
    }

    /**
     * 检查连续负面情感 — 参考 Zendesk 的 sentiment streak detection
     */
    private boolean checkNegativeStreak(Long userId, String currentRunId) {
        if (userId == null) {
            return false;
        }
        try {
            com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SentimentRecord> query =
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SentimentRecord>()
                            .eq(SentimentRecord::getUserId, userId)
                            .orderByDesc(SentimentRecord::getCreateTime)
                            .last("limit " + (NEGATIVE_STREAK_THRESHOLD - 1));
            if (StringUtils.hasText(currentRunId)) {
                query.ne(SentimentRecord::getRunId, currentRunId);
            }
            List<SentimentRecord> recent = recordMapper.selectList(query);
            return recent.size() >= NEGATIVE_STREAK_THRESHOLD - 1
                    && recent.stream().allMatch(r -> "NEGATIVE".equals(r.getSentiment()));
        } catch (Exception e) {
            return false;
        }
    }

    private void saveRecord(String runId, String conversationId, Long userId,
                            String userMessage, SentimentResult result, boolean escalated) {
        try {
            if (recordMapper == null) {
                return;
            }
            SentimentRecord record = new SentimentRecord();
            record.setRecordId(UUID.randomUUID().toString().replace("-", ""));
            record.setRunId(runId);
            record.setConversationId(conversationId);
            record.setUserId(userId);
            record.setUserMessage(userMessage.length() > 500 ? userMessage.substring(0, 500) : userMessage);
            record.setSentiment(result.sentiment);
            record.setIntensity(result.intensity);
            record.setIsUrgent(result.isUrgent ? 1 : 0);
            record.setEmotionTagsJson(JSON.toJSONString(result.emotionTags));
            record.setEscalationTriggered(escalated ? 1 : 0);
            record.setEscalationReason(result.escalationReason);
            record.setCreateTime(new Date());
            record.setEditTime(new Date());
            record.setStatus(1);
            recordMapper.insert(record);
        } catch (Exception e) {
            log.warn("Failed to save sentiment record: {}", e.getMessage());
        }
    }

    public record SentimentResult(String sentiment, double intensity, boolean isUrgent,
                                   List<String> emotionTags, boolean shouldEscalate, String escalationReason) {}
}
