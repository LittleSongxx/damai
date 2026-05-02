package org.javaup.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.javaup.ai.context.AiRequestContextHolder;
import org.javaup.ai.entity.ChatTypeHistory;
import org.javaup.ai.mapper.ChatHistoryMapper;
import org.javaup.ai.service.ChatTypeHistoryService;
import org.javaup.ai.vo.ChatTypeHistoryVo;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * @program: 大麦-ai智能服务项目。 添加 阿星不是程序员 微信，添加时备注 ai 来获取项目的完整资料 
 * @description: 聊天记录服务类实现
 * @author: 阿星不是程序员
 **/
@Service
public class ChatTypeHistoryServiceImpl implements ChatTypeHistoryService {
    
    @Autowired
    private ChatHistoryMapper chatHistoryMapper;
    
    @Autowired 
    private ChatMemory chatMemory;
     
    
    /**
     * 保存会话记录
     * @param type 业务类型
     * @param chatId 会话ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void save(Integer type, String chatId){
        Long userId = AiRequestContextHolder.getRequiredUser().getUserId();
        LambdaQueryWrapper<ChatTypeHistory> chatHistroyLambdaQueryWrapper =
                Wrappers.lambdaQuery(ChatTypeHistory.class)
                        .eq(ChatTypeHistory::getType, type)
                        .eq(ChatTypeHistory::getChatId, chatId)
                        .eq(ChatTypeHistory::getUserId, userId);
        ChatTypeHistory chatTypeHistory = chatHistoryMapper.selectOne(chatHistroyLambdaQueryWrapper);
        if (Objects.isNull(chatTypeHistory)){
            chatTypeHistory = new ChatTypeHistory();
            chatTypeHistory.setType(type);
            chatTypeHistory.setChatId(chatId);
            chatTypeHistory.setUserId(userId);
            chatHistoryMapper.insert(chatTypeHistory);
        }
    }
    
    /**
     * 获取会话ID列表
     * @param type 业务类型
     * @return 会话ID列表
     */
    @Override
    public List<String> getChatIdList(Integer type){
        Long userId = AiRequestContextHolder.getRequiredUser().getUserId();
        LambdaQueryWrapper<ChatTypeHistory> chatHistroyLambdaQueryWrapper =
                Wrappers.lambdaQuery(ChatTypeHistory.class)
                        .eq(ChatTypeHistory::getType, type)
                        .eq(ChatTypeHistory::getUserId, userId)
                        .eq(ChatTypeHistory::getStatus, 1);
        List<ChatTypeHistory> chatTypeHistoryList = chatHistoryMapper.selectList(chatHistroyLambdaQueryWrapper);
        return chatTypeHistoryList.stream()
                .map(ChatTypeHistory::getChatId)
                .toList();
    }
    
    /**
     * 删除会话记录
     * @param type 业务类型
     * @param chatId 会话ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Integer type, String chatId) {
        Long userId = AiRequestContextHolder.getRequiredUser().getUserId();
        LambdaUpdateWrapper<ChatTypeHistory> chatHistroyLambdaUpdateWrapper =
                Wrappers.lambdaUpdate(ChatTypeHistory.class)
                        .eq(ChatTypeHistory::getType, type)
                        .eq(ChatTypeHistory::getChatId, chatId)
                        .eq(ChatTypeHistory::getUserId, userId);
        chatHistoryMapper.delete(chatHistroyLambdaUpdateWrapper);
        chatMemory.clear(chatId);
    }
    
    /**
     * 获取会话
     * @param type 业务类型
     * @param chatId 会话ID
     * @return 会话
     */
    @Override
    public ChatTypeHistory getChatTypeHistory(Integer type, String chatId) {
        Long userId = AiRequestContextHolder.getRequiredUser().getUserId();
        LambdaQueryWrapper<ChatTypeHistory> chatHistroyLambdaQueryWrapper =
                Wrappers.lambdaQuery(ChatTypeHistory.class)
                        .eq(ChatTypeHistory::getType, type)
                        .eq(ChatTypeHistory::getChatId, chatId)
                        .eq(ChatTypeHistory::getUserId, userId)
                        .eq(ChatTypeHistory::getStatus, 1);
        return chatHistoryMapper.selectOne(chatHistroyLambdaQueryWrapper);
    }
    
    @Override
    public void updateById(ChatTypeHistory chatTypeHistory){
        chatHistoryMapper.updateById(chatTypeHistory);
    }

    @Override
    public void bindLatestRun(String chatId, String runId, String workflowStatus) {
        Long userId = AiRequestContextHolder.getRequiredUser().getUserId();
        ChatTypeHistory chatTypeHistory = chatHistoryMapper.selectOne(Wrappers.lambdaQuery(ChatTypeHistory.class)
                .eq(ChatTypeHistory::getChatId, chatId)
                .eq(ChatTypeHistory::getUserId, userId)
                .eq(ChatTypeHistory::getStatus, 1)
                .last("limit 1"));
        if (chatTypeHistory == null) {
            return;
        }
        chatTypeHistory.setLatestRunId(runId);
        chatTypeHistory.setWorkflowStatus(workflowStatus);
        chatHistoryMapper.updateById(chatTypeHistory);
    }
    
    /**
     * 获取会话ID列表
     * @param type 业务类型
     * @return 会话ID列表
     */
    @Override
    public List<ChatTypeHistoryVo> getChatTypeHistoryList(Integer type){
        Long userId = AiRequestContextHolder.getRequiredUser().getUserId();
        LambdaQueryWrapper<ChatTypeHistory> chatHistroyLambdaQueryWrapper =
                Wrappers.lambdaQuery(ChatTypeHistory.class)
                        .eq(ChatTypeHistory::getType, type)
                        .eq(ChatTypeHistory::getUserId, userId)
                        .eq(ChatTypeHistory::getStatus, 1)
                        .orderByDesc(ChatTypeHistory::getEditTime);
        List<ChatTypeHistory> chatTypeHistoryList = chatHistoryMapper.selectList(chatHistroyLambdaQueryWrapper);
        return chatTypeHistoryList.stream()
                .map(chatTypeHistory -> {
                    ChatTypeHistoryVo chatTypeHistoryVo = new ChatTypeHistoryVo();
                    BeanUtils.copyProperties(chatTypeHistory, chatTypeHistoryVo);
                    return chatTypeHistoryVo;
                })
                .toList();
    }
}
