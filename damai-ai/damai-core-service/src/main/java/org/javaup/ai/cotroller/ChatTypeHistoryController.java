package org.javaup.ai.cotroller;

import lombok.RequiredArgsConstructor;
import org.javaup.ai.assistant.AssistantRouteType;
import org.javaup.ai.assistant.AssistantConversationService;
import org.javaup.ai.common.ApiResponse;
import org.javaup.ai.entity.ChatTypeHistory;
import org.javaup.ai.enums.ChatType;
import org.javaup.ai.service.ChatTypeHistoryService;
import org.javaup.ai.vo.ChatHistoryMessageVO;
import org.javaup.ai.vo.AssistantConversationVo;
import org.javaup.ai.vo.ChatTypeHistoryVo;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @program: 大麦-ai智能服务项目。 添加 阿星不是程序员 微信，添加时备注 ai 来获取项目的完整资料 
 * @description: 聊天记录控制器
 * @author: 阿星不是程序员
 **/
@RequiredArgsConstructor
@RestController
@RequestMapping("/chat")
public class ChatTypeHistoryController {

    private final ChatTypeHistoryService chatHistoryService;

    private final AssistantConversationService assistantConversationService;

    private final org.javaup.ai.assistant.compat.LegacyAssistantCompatibilityService legacyAssistantCompatibilityService;

    private final ChatMemory chatMemory;
    
    @RequestMapping("/type/history/list")
    public List<ChatTypeHistoryVo> getChatTypeHistoryList(@RequestParam("type") Integer type) {
        List<ChatTypeHistoryVo> legacyHistory = chatHistoryService.getChatTypeHistoryList(type);
        AssistantRouteType routeType = routeTypeOf(type);
        if (routeType == null) {
            return legacyHistory;
        }
        Map<String, ChatTypeHistoryVo> merged = new LinkedHashMap<>();
        for (ChatTypeHistoryVo history : legacyHistory) {
            merged.put(history.getChatId(), history);
        }
        for (AssistantConversationVo conversation : assistantConversationService.listConversations()) {
            if (!routeType.getCode().equalsIgnoreCase(conversation.getRouteType())) {
                continue;
            }
            merged.putIfAbsent(conversation.getChatId(), toLegacyHistoryVo(type, conversation));
        }
        return merged.values().stream().toList();
    }

    @RequestMapping("/history/message/list")
    public List<ChatHistoryMessageVO> getChatHistory(@RequestParam("chatId") String chatId,@RequestParam("type") Integer type) {
        ChatTypeHistory session = chatHistoryService.getChatTypeHistory(type, chatId);
        if (session == null && assistantConversationService.getConversation(chatId) == null) {
            return List.of();
        }
        List<Message> messages = chatMemory.get(chatId);
        return messages.stream().map(ChatHistoryMessageVO::new).toList();
    }
    
    @RequestMapping(value = "/delete")
    public ApiResponse<Void> delete(@RequestParam("type") Integer type, @RequestParam("chatId") String chatId){
        chatHistoryService.delete(type, chatId);
        if (routeTypeOf(type) != null) {
            assistantConversationService.deleteConversation(chatId);
        }
        return ApiResponse.ok();
    }

    private AssistantRouteType routeTypeOf(Integer type) {
        if (ChatType.ASSISTANT.getCode().equals(type)) {
            return AssistantRouteType.BUSINESS;
        }
        if (ChatType.MARKDOWN.getCode().equals(type)) {
            return AssistantRouteType.KNOWLEDGE;
        }
        if (ChatType.ANALYSIS.getCode().equals(type)) {
            return AssistantRouteType.OPS;
        }
        return null;
    }

    private ChatTypeHistoryVo toLegacyHistoryVo(Integer type, AssistantConversationVo conversation) {
        ChatTypeHistoryVo historyVo = new ChatTypeHistoryVo();
        historyVo.setType(type);
        historyVo.setChatId(conversation.getChatId());
        historyVo.setTitle(conversation.getTitle());
        historyVo.setLatestRunId(conversation.getLatestRunId());
        historyVo.setWorkflowStatus(legacyAssistantCompatibilityService.toLegacyWorkflowStatus(conversation.getLatestStatus()));
        return historyVo;
    }
}
