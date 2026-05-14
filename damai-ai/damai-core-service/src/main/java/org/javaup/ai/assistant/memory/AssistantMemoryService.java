package org.javaup.ai.assistant.memory;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.assistant.runtime.AssistantObservedChatService;
import org.javaup.ai.config.StructuredMemoryProperties;
import org.javaup.ai.entity.AiConversationMemorySummary;
import org.javaup.ai.entity.AiRun;
import org.javaup.ai.mapper.AiConversationMemorySummaryMapper;
import org.javaup.ai.mapper.AiRunMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
public class AssistantMemoryService {

    private static final int COMPRESSION_TRIGGER_RUNS = 6;
    private static final int RECENT_RUN_LIMIT = 8;
    private static final int SUMMARY_CHAR_LIMIT = 1200;
    private static final int STRUCTURED_MEMORY_VERSION = 2;

    private final AiConversationMemorySummaryMapper memorySummaryMapper;
    private final AiRunMapper runMapper;
    private final OpenAiChatModel chatModel;
    private final AssistantObservedChatService observedChatService;
    private final StructuredMemoryProperties structuredMemoryProperties;

    public AssistantMemoryService(AiConversationMemorySummaryMapper memorySummaryMapper,
                                  AiRunMapper runMapper,
                                  OpenAiChatModel chatModel) {
        this(memorySummaryMapper, runMapper, chatModel, null, defaultStructuredMemoryProperties());
    }

    @Autowired
    public AssistantMemoryService(AiConversationMemorySummaryMapper memorySummaryMapper,
                                  AiRunMapper runMapper,
                                  OpenAiChatModel chatModel,
                                  AssistantObservedChatService observedChatService,
                                  StructuredMemoryProperties structuredMemoryProperties) {
        this.memorySummaryMapper = memorySummaryMapper;
        this.runMapper = runMapper;
        this.chatModel = chatModel;
        this.observedChatService = observedChatService;
        this.structuredMemoryProperties = structuredMemoryProperties == null ? defaultStructuredMemoryProperties() : structuredMemoryProperties;
    }

    public AssistantMemoryContext load(String conversationId, Long userId) {
        AiConversationMemorySummary summary = latestSummary(conversationId, userId);
        if (summary == null || !StringUtils.hasText(summary.getSummary())) {
            return AssistantMemoryContext.empty();
        }
        return new AssistantMemoryContext(summary.getSummary(), true, parseStructuredMemory(summary));
    }

    @Transactional(rollbackFor = Exception.class)
    public void refreshAfterRun(AiRun run) {
        if (run == null || !StringUtils.hasText(run.getConversationId()) || run.getUserId() == null) {
            return;
        }
        List<AiRun> recentRuns = recentCompletedRuns(run.getConversationId(), run.getUserId());
        if (recentRuns.size() < COMPRESSION_TRIGGER_RUNS) {
            return;
        }
        AiConversationMemorySummary latest = latestSummary(run.getConversationId(), run.getUserId());
        if (latest != null && run.getRunId().equals(latest.getCoveredRunId())) {
            return;
        }
        AssistantStructuredMemory structuredMemory = buildStructuredMemory(run, recentRuns, latest);
        String summaryText = structuredMemory == null ? buildSummary(recentRuns) : structuredMemory.summary();
        if (!StringUtils.hasText(summaryText)) {
            return;
        }
        AiConversationMemorySummary summary = new AiConversationMemorySummary();
        summary.setConversationId(run.getConversationId());
        summary.setUserId(run.getUserId());
        summary.setCoveredRunId(run.getRunId());
        summary.setSummary(summaryText);
        summary.setMemoryJson(structuredMemory == null ? null : JSON.toJSONString(structuredMemory));
        summary.setSummaryVersion(STRUCTURED_MEMORY_VERSION);
        summary.setCompressionCount(latest == null ? 1 : latest.getCompressionCount() + 1);
        summary.setStatus(1);
        memorySummaryMapper.insert(summary);
    }

    private AiConversationMemorySummary latestSummary(String conversationId, Long userId) {
        return memorySummaryMapper.selectOne(Wrappers.lambdaQuery(AiConversationMemorySummary.class)
                .eq(AiConversationMemorySummary::getConversationId, conversationId)
                .eq(AiConversationMemorySummary::getUserId, userId)
                .eq(AiConversationMemorySummary::getStatus, 1)
                .orderByDesc(AiConversationMemorySummary::getCreateTime)
                .last("limit 1"));
    }

    private List<AiRun> recentCompletedRuns(String conversationId, Long userId) {
        return runMapper.selectList(Wrappers.lambdaQuery(AiRun.class)
                .eq(AiRun::getConversationId, conversationId)
                .eq(AiRun::getUserId, userId)
                .eq(AiRun::getStatus, 1)
                .orderByDesc(AiRun::getCreateTime)
                .last("limit " + RECENT_RUN_LIMIT));
    }

    private String buildSummary(List<AiRun> recentRuns) {
        String rawConversation = recentRuns.stream()
                .filter(run -> StringUtils.hasText(run.getUserMessage()) || StringUtils.hasText(run.getResponseSummary()))
                .sorted(Comparator.comparing(AiRun::getCreateTime, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(run -> "用户：" + safe(run.getUserMessage()) + "\n助手：" + safe(run.getResponseSummary()))
                .collect(Collectors.joining("\n---\n"));
        if (!StringUtils.hasText(rawConversation)) {
            return "";
        }
        try {
            String result = ChatClient.builder(chatModel).build().prompt()
                    .user("""
                            请将以下对话历史压缩为一段简洁摘要（不超过300字），保留关键的用户偏好、查询过的节目/城市/票档信息和重要结论，省略寒暄和重复内容。

                            对话历史：
                            %s

                            只返回摘要内容，不要其他格式。
                            """.formatted(rawConversation))
                    .call()
                    .content();
            if (StringUtils.hasText(result)) {
                return result;
            }
        } catch (Exception ex) {
            log.warn("LLM 会话摘要压缩失败，回退为截断方式", ex);
        }
        if (rawConversation.length() <= SUMMARY_CHAR_LIMIT) {
            return rawConversation;
        }
        return rawConversation.substring(rawConversation.length() - SUMMARY_CHAR_LIMIT);
    }

    private AssistantStructuredMemory buildStructuredMemory(AiRun run,
                                                            List<AiRun> recentRuns,
                                                            AiConversationMemorySummary latest) {
        AssistantStructuredMemory heuristic = fallbackStructuredMemory(recentRuns);
        if (!structuredMemoryProperties.isEnabled() || observedChatService == null) {
            return heuristic;
        }
        String rawConversation = recentRuns.stream()
                .filter(item -> StringUtils.hasText(item.getUserMessage()) || StringUtils.hasText(item.getResponseSummary()))
                .sorted(Comparator.comparing(AiRun::getCreateTime, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(item -> "用户：" + safe(item.getUserMessage()) + "\n助手：" + safe(item.getResponseSummary()))
                .collect(Collectors.joining("\n---\n"));
        if (!StringUtils.hasText(rawConversation)) {
            return heuristic;
        }
        String previousMemory = latest == null || !StringUtils.hasText(latest.getMemoryJson())
                ? "无"
                : latest.getMemoryJson();
        String prompt = """
                你是大麦助手的长期记忆压缩器。请根据已有记忆和最新多轮对话，输出 JSON 对象。

                字段要求：
                - summary: 120字以内中文摘要
                - conversationGoal: 当前用户这段会话的主要目标
                - stableFacts: 长度 0-6 的字符串数组，只保留稳定事实或偏好
                - pendingQuestions: 长度 0-4 的字符串数组，记录尚未解决的问题
                - retrievalHints: 长度 0-6 的字符串数组，记录后续检索可复用的关键词

                规则：
                1. 只输出 JSON，不要 Markdown
                2. 没有内容的数组返回 []
                3. 不要编造用户未说过的事实

                已有记忆：
                %s

                最新对话：
                %s
                """.formatted(previousMemory, rawConversation);
        try {
            String content = observedChatService.call(
                    ChatClient.builder(chatModel).build(),
                    "MEMORY_SUMMARY",
                    "AssistantMemory",
                    "qwen3.6-plus",
                    prompt
            );
            AssistantStructuredMemory parsed = parseStructuredMemory(content);
            return parsed == null ? heuristic : mergeWithHeuristic(parsed, heuristic);
        } catch (Exception ex) {
            log.warn("结构化会话记忆生成失败，使用启发式摘要", ex);
            return heuristic;
        }
    }

    private AssistantStructuredMemory mergeWithHeuristic(AssistantStructuredMemory parsed, AssistantStructuredMemory heuristic) {
        return AssistantStructuredMemory.builder()
                .summary(StringUtils.hasText(parsed.summary()) ? parsed.summary() : heuristic.summary())
                .conversationGoal(StringUtils.hasText(parsed.conversationGoal()) ? parsed.conversationGoal() : heuristic.conversationGoal())
                .stableFacts(defaultIfEmpty(parsed.stableFacts(), heuristic.stableFacts()))
                .pendingQuestions(defaultIfEmpty(parsed.pendingQuestions(), heuristic.pendingQuestions()))
                .retrievalHints(defaultIfEmpty(parsed.retrievalHints(), heuristic.retrievalHints()))
                .build();
    }

    private AssistantStructuredMemory fallbackStructuredMemory(List<AiRun> recentRuns) {
        String summary = buildSummary(recentRuns);
        AiRun latestRun = recentRuns.stream()
                .filter(Objects::nonNull)
                .max(Comparator.comparing(AiRun::getCreateTime, Comparator.nullsLast(Comparator.naturalOrder())))
                .orElse(null);
        List<String> stableFacts = recentRuns.stream()
                .flatMap(run -> Stream.of(run.getUserMessage(), run.getResponseSummary()))
                .map(this::safe)
                .filter(StringUtils::hasText)
                .distinct()
                .limit(structuredMemoryProperties.getStableFactLimit())
                .toList();
        List<String> pendingQuestions = latestRun != null && !StringUtils.hasText(latestRun.getResponseSummary())
                ? List.of(safe(latestRun.getUserMessage()))
                : List.of();
        List<String> retrievalHints = extractRetrievalHints(recentRuns);
        return AssistantStructuredMemory.builder()
                .summary(summary)
                .conversationGoal(latestRun == null ? "" : safe(latestRun.getUserMessage()))
                .stableFacts(stableFacts)
                .pendingQuestions(pendingQuestions.stream().limit(structuredMemoryProperties.getPendingQuestionLimit()).toList())
                .retrievalHints(retrievalHints)
                .build();
    }

    private List<String> extractRetrievalHints(List<AiRun> recentRuns) {
        List<String> keywords = List.of("退票", "退款", "实名", "入场", "儿童票", "电子票", "票档", "配送", "快递", "取票", "发票", "安检", "转赠", "支付", "订单", "抢票");
        Set<String> hints = recentRuns.stream()
                .flatMap(run -> Stream.of(run.getUserMessage(), run.getResponseSummary()))
                .filter(StringUtils::hasText)
                .flatMap(text -> keywords.stream().filter(text::contains))
                .limit(structuredMemoryProperties.getRetrievalHintLimit())
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        return new java.util.ArrayList<>(hints);
    }

    private AssistantStructuredMemory parseStructuredMemory(AiConversationMemorySummary summary) {
        if (summary == null || !StringUtils.hasText(summary.getMemoryJson())) {
            return AssistantStructuredMemory.builder()
                    .summary(summary == null ? "" : summary.getSummary())
                    .conversationGoal("")
                    .stableFacts(List.of())
                    .pendingQuestions(List.of())
                    .retrievalHints(List.of())
                    .build();
        }
        AssistantStructuredMemory parsed = parseStructuredMemory(summary.getMemoryJson());
        if (parsed == null) {
            return AssistantStructuredMemory.builder()
                    .summary(summary.getSummary())
                    .conversationGoal("")
                    .stableFacts(List.of())
                    .pendingQuestions(List.of())
                    .retrievalHints(List.of())
                    .build();
        }
        return parsed;
    }

    private AssistantStructuredMemory parseStructuredMemory(String content) {
        if (!StringUtils.hasText(content)) {
            return null;
        }
        try {
            JSONObject object = JSON.parseObject(content);
            return AssistantStructuredMemory.builder()
                    .summary(defaultString(object.getString("summary")))
                    .conversationGoal(defaultString(object.getString("conversationGoal")))
                    .stableFacts(toStringList(object.getJSONArray("stableFacts")))
                    .pendingQuestions(toStringList(object.getJSONArray("pendingQuestions")))
                    .retrievalHints(toStringList(object.getJSONArray("retrievalHints")))
                    .build();
        } catch (Exception ex) {
            log.warn("解析结构化会话记忆失败", ex);
            return null;
        }
    }

    private String safe(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().replaceAll("\\s+", " ");
    }

    private List<String> toStringList(com.alibaba.fastjson.JSONArray array) {
        if (array == null || array.isEmpty()) {
            return List.of();
        }
        return array.stream()
                .map(item -> item == null ? "" : String.valueOf(item).trim())
                .filter(StringUtils::hasText)
                .toList();
    }

    private List<String> defaultIfEmpty(List<String> preferred, List<String> fallback) {
        return preferred == null || preferred.isEmpty() ? fallback : preferred;
    }

    private String defaultString(String value) {
        return value == null ? "" : value.trim();
    }

    private static StructuredMemoryProperties defaultStructuredMemoryProperties() {
        return new StructuredMemoryProperties();
    }
}
