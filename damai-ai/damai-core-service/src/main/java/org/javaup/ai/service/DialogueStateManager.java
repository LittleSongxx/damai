package org.javaup.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.assistant.runtime.AssistantObservedChatService;
import org.javaup.ai.entity.DialogueState;
import org.javaup.ai.mapper.DialogueStateMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 多轮对话状态机 - 参考 Rasa 的 slot-filling 设计。
 * 维护对话阶段和槽位状态，引导用户逐步补全信息。
 *
 * 状态转换: INTENT_IDENTIFIED -> SLOT_FILLING -> CONFIRMATION -> EXECUTION -> CLOSED
 */
@Slf4j
@Service
public class DialogueStateManager {

    private final DialogueStateMapper stateMapper;
    private final ChatClient chatClient;
    private final AssistantObservedChatService observedChatService;

    private static final int DEFAULT_MAX_TURNS = 10;

    // Ticket-specific slot definitions following Rasa's domain.yml slot design
    public static final List<SlotDefinition> BUY_TICKET_SLOTS = List.of(
            new SlotDefinition("city", "城市", "演出城市", true, "text"),
            new SlotDefinition("category", "演出类型", "演唱会/脱口秀/话剧等", true, "text"),
            new SlotDefinition("artist", "艺人/节目名", "艺人名或节目名称", false, "text"),
            new SlotDefinition("date", "日期", "希望观看的日期", false, "text"),
            new SlotDefinition("price", "票价", "期望的票价区间或档位", true, "text"),
            new SlotDefinition("ticketCount", "数量", "购票张数", true, "integer"),
            new SlotDefinition("ticketUsers", "购票人", "观演人姓名或证件号", true, "list"),
            new SlotDefinition("mobile", "手机号", "接收订单通知的手机号", false, "text")
    );

    public static final List<SlotDefinition> QUERY_PROGRAM_SLOTS = List.of(
            new SlotDefinition("city", "城市", "演出城市", false, "text"),
            new SlotDefinition("category", "演出类型", "演唱会/脱口秀/话剧等", false, "text"),
            new SlotDefinition("artist", "艺人/节目名", "艺人名或节目名称", false, "text"),
            new SlotDefinition("date", "日期", "希望观看的日期", false, "text")
    );

    public DialogueStateManager(DialogueStateMapper stateMapper,
                                @Qualifier("unifiedBusinessChatClient") ChatClient chatClient,
                                AssistantObservedChatService observedChatService) {
        this.stateMapper = stateMapper;
        this.chatClient = chatClient;
        this.observedChatService = observedChatService;
    }

    /**
     * 获取或创建对话状态
     */
    @Transactional
    public DialogueContext getOrCreate(String conversationId, Long userId, String userMessage) {
        DialogueState state = stateMapper.selectOne(
                Wrappers.lambdaQuery(DialogueState.class)
                        .eq(DialogueState::getConversationId, conversationId)
                        .eq(DialogueState::getUserId, userId)
                        .eq(DialogueState::getResolved, 0)
                        .orderByDesc(DialogueState::getCreateTime)
                        .last("limit 1"));

        if (state == null) {
            state = createState(conversationId, userId);
        }

        Map<String, Object> slots = parseSlots(state.getSlotsJson());
        return new DialogueContext(state, slots);
    }

    /**
     * 更新对话状态：分析用户消息，填充槽位。
     * 参考 Rasa 的 slot extraction + next action prediction。
     */
    @Transactional
    public StateTransitionResult updateState(DialogueContext context, String userMessage, String intent) {
        DialogueState state = context.state;
        state.setTurnCount((state.getTurnCount() == null ? 0 : state.getTurnCount()) + 1);
        state.setLastUserMessage(userMessage);

        // Determine dialogue phase and slots based on intent
        if (state.getIntent() == null && StringUtils.hasText(intent)) {
            state.setIntent(intent);
            state.setDialoguePhase("INTENT_IDENTIFIED");
        }

        List<SlotDefinition> requiredSlots = getSlotsForIntent(state.getIntent());

        // Extract slots from user message using LLM (Rasa's NLU slot extraction equivalent)
        Map<String, Object> extractedSlots = extractSlots(userMessage, state.getIntent(), requiredSlots);
        Map<String, Object> currentSlots = context.slots;
        currentSlots.putAll(extractedSlots);

        // Find missing required slots
        List<SlotDefinition> missingSlots = requiredSlots.stream()
                .filter(s -> s.required && !currentSlots.containsKey(s.name))
                .collect(Collectors.toList());

        state.setSlotsJson(JSON.toJSONString(currentSlots));
        state.setMissingSlotsJson(JSON.toJSONString(missingSlots.stream()
                .map(s -> Map.of("name", s.name, "label", s.label, "description", s.description))
                .toList()));

        String nextPhase;
        String responseMessage;

        if (missingSlots.isEmpty()) {
            // All slots filled, move to confirmation
            nextPhase = "CONFIRMATION";
            responseMessage = generateConfirmationMessage(currentSlots, state.getIntent());
        } else if (state.getTurnCount() >= (state.getMaxTurns() == null ? DEFAULT_MAX_TURNS : state.getMaxTurns())) {
            // Max turns exceeded
            nextPhase = "EXECUTION";
            responseMessage = "已收集到足够信息，正在为你处理...";
        } else {
            // Ask for missing slots
            nextPhase = "SLOT_FILLING";
            responseMessage = generateSlotPrompt(missingSlots, currentSlots);
        }

        state.setDialoguePhase(nextPhase);
        state.setLastAssistantMessage(responseMessage);
        state.setEditTime(new Date());
        stateMapper.updateById(state);

        return new StateTransitionResult(nextPhase, responseMessage, currentSlots, missingSlots);
    }

    /**
     * 标记对话已解决
     */
    @Transactional
    public void markResolved(DialogueContext context) {
        context.state.setResolved(1);
        context.state.setResolveTime(new Date());
        context.state.setEditTime(new Date());
        stateMapper.updateById(context.state);
    }

    /**
     * 使用LLM提取槽位 - 参考Rasa的DIET classifier做实体提取。
     */
    private Map<String, Object> extractSlots(String userMessage, String intent, List<SlotDefinition> slotDefs) {
        if (!StringUtils.hasText(userMessage) || slotDefs.isEmpty()) {
            return Map.of();
        }
        try {
            String slotNames = slotDefs.stream()
                    .map(s -> s.name + "(" + s.type + "): " + s.description)
                    .collect(Collectors.joining(", "));

            String prompt = """
                    你是票务系统的槽位提取助手。从用户消息中提取以下槽位信息。
                    意向: %s
                    槽位定义: %s
                    用户消息: "%s"

                    返回JSON对象，只包含从消息中明确提取到的槽位。未提到的槽位不要包含。
                    示例: {"city":"北京","category":"演唱会","artist":"周杰伦"}
                    只输出JSON，不要其他文本。
                    """.formatted(intent, slotNames, userMessage);

            String result = chatClient.prompt().user(prompt).call().content();
            if (StringUtils.hasText(result)) {
                result = result.trim();
                if (result.startsWith("```")) {
                    result = result.replaceAll("```\\w*\\n?", "").replaceAll("```", "").trim();
                }
                JSONObject json = JSON.parseObject(result);
                Map<String, Object> slots = new LinkedHashMap<>();
                json.forEach((k, v) -> {
                    if (v != null && StringUtils.hasText(String.valueOf(v))) {
                        slots.put(k, v);
                    }
                });
                return slots;
            }
        } catch (Exception e) {
            log.warn("Slot extraction failed: {}", e.getMessage());
        }
        return Map.of();
    }

    private String generateSlotPrompt(List<SlotDefinition> missingSlots, Map<String, Object> filledSlots) {
        if (missingSlots.isEmpty()) return "";
        SlotDefinition next = missingSlots.get(0);
        StringBuilder sb = new StringBuilder();
        sb.append("还需要确认以下信息：").append(next.label).append("（").append(next.description).append("）");
        if (!filledSlots.isEmpty()) {
            sb.append("\n已确认的信息：");
            filledSlots.forEach((k, v) -> sb.append(k).append("=").append(v).append(" "));
        }
        return sb.toString();
    }

    private String generateConfirmationMessage(Map<String, Object> slots, String intent) {
        StringBuilder sb = new StringBuilder("请确认以下信息：\n");
        slots.forEach((k, v) -> sb.append("• ").append(k).append("：").append(v).append("\n"));
        sb.append("\n确认无误请回复「确认」，需要修改请说明。");
        return sb.toString();
    }

    private List<SlotDefinition> getSlotsForIntent(String intent) {
        if (intent == null) return List.of();
        return switch (intent) {
            case "BUY_TICKET" -> BUY_TICKET_SLOTS;
            case "QUERY_PROGRAM" -> QUERY_PROGRAM_SLOTS;
            default -> List.of();
        };
    }

    private DialogueState createState(String conversationId, Long userId) {
        DialogueState state = new DialogueState();
        state.setStateId(UUID.randomUUID().toString().replace("-", ""));
        state.setConversationId(conversationId);
        state.setUserId(userId);
        state.setDialoguePhase("INTENT_IDENTIFIED");
        state.setSlotsJson("{}");
        state.setMissingSlotsJson("[]");
        state.setTurnCount(0);
        state.setMaxTurns(DEFAULT_MAX_TURNS);
        state.setResolved(0);
        state.setCreateTime(new Date());
        state.setEditTime(new Date());
        state.setStatus(1);
        stateMapper.insert(state);
        return state;
    }

    private Map<String, Object> parseSlots(String slotsJson) {
        if (!StringUtils.hasText(slotsJson) || "{}".equals(slotsJson)) {
            return new LinkedHashMap<>();
        }
        try {
            JSONObject json = JSON.parseObject(slotsJson);
            Map<String, Object> slots = new LinkedHashMap<>();
            json.forEach(slots::put);
            return slots;
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /**
     * Slot definition - following Rasa's domain slot schema
     */
    public record SlotDefinition(String name, String label, String description, boolean required, String type) {}

    /**
     * Context holding dialogue state and parsed slots
     */
    public record DialogueContext(DialogueState state, Map<String, Object> slots) {
        public boolean isPhase(String phase) {
            return phase.equals(state.getDialoguePhase());
        }

        public boolean isResolved() {
            return state.getResolved() != null && state.getResolved() == 1;
        }
    }

    /**
     * Result of a state transition
     */
    public record StateTransitionResult(String nextPhase, String responseMessage,
                                         Map<String, Object> slots, List<SlotDefinition> missingSlots) {
        public boolean allSlotsFilled() {
            return missingSlots.isEmpty();
        }
    }
}
