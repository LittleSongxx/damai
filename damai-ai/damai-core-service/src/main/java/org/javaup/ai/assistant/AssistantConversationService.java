package org.javaup.ai.assistant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.assistant.memory.AssistantMemoryKeyService;
import org.javaup.ai.context.AiRequestContextHolder;
import org.javaup.ai.entity.AiConversation;
import org.javaup.ai.entity.AiAction;
import org.javaup.ai.entity.AiRetrieval;
import org.javaup.ai.entity.AiRun;
import org.javaup.ai.entity.AiRunEvent;
import org.javaup.ai.entity.AiToolCall;
import org.javaup.ai.mapper.AiConversationMapper;
import org.javaup.ai.mapper.AiActionMapper;
import org.javaup.ai.mapper.AiRetrievalMapper;
import org.javaup.ai.mapper.AiRunEventMapper;
import org.javaup.ai.mapper.AiRunMapper;
import org.javaup.ai.mapper.AiToolCallMapper;
import org.javaup.ai.vo.AssistantConversationVo;
import org.javaup.ai.vo.ChatHistoryMessageVO;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AssistantConversationService {

    private final AiConversationMapper conversationMapper;
    private final AiRunMapper runMapper;
    private final AiRunEventMapper runEventMapper;
    private final AiActionMapper actionMapper;
    private final AiToolCallMapper toolCallMapper;
    private final AiRetrievalMapper retrievalMapper;
    private final ChatMemory chatMemory;
    private final AssistantMemoryKeyService memoryKeyService;

    @Transactional(rollbackFor = Exception.class)
    public AiConversation ensureConversation(String chatId, String titleCandidate) {
        Long userId = AiRequestContextHolder.getRequiredUser().getUserId();
        AiConversation conversation = conversationMapper.selectOne(query(chatId, userId));
        if (conversation != null) {
            if (!StringUtils.hasText(conversation.getTitle()) && StringUtils.hasText(titleCandidate)) {
                conversation.setTitle(shortTitle(titleCandidate));
                conversationMapper.updateById(conversation);
            }
            return conversation;
        }
        conversation = new AiConversation();
        conversation.setConversationId(chatId);
        conversation.setUserId(userId);
        conversation.setTitle(shortTitle(titleCandidate));
        conversation.setStatus(1);
        conversationMapper.insert(conversation);
        return conversation;
    }

    public AiConversation getConversation(String chatId) {
        Long userId = AiRequestContextHolder.getRequiredUser().getUserId();
        return conversationMapper.selectOne(query(chatId, userId));
    }

    public List<AssistantConversationVo> listConversations() {
        Long userId = AiRequestContextHolder.getRequiredUser().getUserId();
        List<AiConversation> conversations = conversationMapper.selectList(Wrappers.lambdaQuery(AiConversation.class)
                .eq(AiConversation::getUserId, userId)
                .eq(AiConversation::getStatus, 1)
                .orderByDesc(AiConversation::getEditTime));
        return conversations.stream().map(conversation -> {
            AssistantConversationVo view = new AssistantConversationVo();
            view.setChatId(conversation.getConversationId());
            view.setTitle(conversation.getTitle());
            view.setRouteType(conversation.getRouteType());
            view.setLatestRunId(conversation.getLatestRunId());
            view.setLatestStatus(conversation.getLatestStatus());
            return view;
        }).toList();
    }

    public List<ChatHistoryMessageVO> listMessages(String chatId) {
        AiConversation conversation = getConversation(chatId);
        if (conversation == null) {
            return List.of();
        }
        List<Message> messages = chatMemory.get(memoryKeyService.currentUserConversationKey(chatId));
        return messages.stream().map(ChatHistoryMessageVO::new).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public void bindLatestRun(String chatId, AssistantRouteType routeType, String runId, String runStatus, String titleCandidate) {
        AiConversation conversation = ensureConversation(chatId, titleCandidate);
        conversation.setRouteType(routeType == null ? conversation.getRouteType() : routeType.getCode());
        conversation.setLatestRunId(runId);
        conversation.setLatestStatus(runStatus);
        if (!StringUtils.hasText(conversation.getTitle())) {
            conversation.setTitle(shortTitle(titleCandidate));
        }
        conversationMapper.updateById(conversation);
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteConversation(String chatId) {
        Long userId = AiRequestContextHolder.getRequiredUser().getUserId();
        conversationMapper.update(null, Wrappers.lambdaUpdate(AiConversation.class)
                .set(AiConversation::getStatus, 0)
                .eq(AiConversation::getConversationId, chatId)
                .eq(AiConversation::getUserId, userId)
                .eq(AiConversation::getStatus, 1));
        runMapper.update(null, Wrappers.lambdaUpdate(AiRun.class)
                .set(AiRun::getStatus, 0)
                .eq(AiRun::getConversationId, chatId)
                .eq(AiRun::getUserId, userId)
                .eq(AiRun::getStatus, 1));
        runEventMapper.update(null, Wrappers.lambdaUpdate(AiRunEvent.class)
                .set(AiRunEvent::getStatus, 0)
                .eq(AiRunEvent::getConversationId, chatId)
                .eq(AiRunEvent::getUserId, userId)
                .eq(AiRunEvent::getStatus, 1));
        actionMapper.update(null, Wrappers.lambdaUpdate(AiAction.class)
                .set(AiAction::getStatus, 0)
                .eq(AiAction::getConversationId, chatId)
                .eq(AiAction::getUserId, userId)
                .eq(AiAction::getStatus, 1));
        toolCallMapper.update(null, Wrappers.lambdaUpdate(AiToolCall.class)
                .set(AiToolCall::getStatus, 0)
                .eq(AiToolCall::getConversationId, chatId)
                .eq(AiToolCall::getUserId, userId)
                .eq(AiToolCall::getStatus, 1));
        retrievalMapper.update(null, Wrappers.lambdaUpdate(AiRetrieval.class)
                .set(AiRetrieval::getStatus, 0)
                .eq(AiRetrieval::getConversationId, chatId)
                .eq(AiRetrieval::getUserId, userId)
                .eq(AiRetrieval::getStatus, 1));
        chatMemory.clear(memoryKeyService.currentUserConversationKey(chatId));
    }

    private LambdaQueryWrapper<AiConversation> query(String chatId, Long userId) {
        return Wrappers.lambdaQuery(AiConversation.class)
                .eq(AiConversation::getConversationId, chatId)
                .eq(AiConversation::getUserId, userId)
                .eq(AiConversation::getStatus, 1)
                .last("limit 1");
    }

    private String shortTitle(String message) {
        if (!StringUtils.hasText(message)) {
            return "新的 AI 会话";
        }
        String compact = message.trim().replaceAll("\\s+", " ");
        return compact.length() > 24 ? compact.substring(0, 24) + "..." : compact;
    }
}
