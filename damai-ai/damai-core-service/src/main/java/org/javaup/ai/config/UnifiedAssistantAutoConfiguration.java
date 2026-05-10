package org.javaup.ai.config;

import org.javaup.ai.advisor.AiObservabilityAdvisor;
import org.javaup.ai.assistant.skill.business.BusinessToolService;
import org.javaup.ai.service.AiObservabilityService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.javaup.ai.constants.DaMaiConstant.MESSAGE_CHAT_MEMORY_ADVISOR_ORDER;
import static org.javaup.ai.constants.DaMaiConstant.OBSERVABILITY_ADVISOR_ORDER;

@Configuration
public class UnifiedAssistantAutoConfiguration {

    @Bean
    public ChatClient unifiedBusinessChatClient(OpenAiChatModel model,
                                                ChatMemory chatMemory,
                                                BusinessToolService businessToolService,
                                                AiObservabilityService observabilityService) {
        return ChatClient.builder(model)
                .defaultSystem("""
                        你是统一的大麦智能助手，当前负责购票和节目业务。
                        你必须优先使用工具获取节目、票档和下单预览，不能编造节目数据。
                        对于下单请求，只能生成购票预览，不能直接创建订单。
                        票档只能告知是否可购和票档信息，不能泄露具体余票数量。
                        """)
                .defaultAdvisors(
                        new SimpleLoggerAdvisor(),
                        MessageChatMemoryAdvisor.builder(chatMemory).order(MESSAGE_CHAT_MEMORY_ADVISOR_ORDER).build(),
                        AiObservabilityAdvisor.builder(observabilityService)
                                .order(OBSERVABILITY_ADVISOR_ORDER)
                                .modelName("qwen3.6-plus")
                                .requestType("UnifiedBusiness")
                                .build()
                )
                .defaultTools(businessToolService)
                .build();
    }

    @Bean
    public ChatClient unifiedKnowledgeChatClient(OpenAiChatModel model,
                                                 ChatMemory chatMemory,
                                                 AiObservabilityService observabilityService) {
        return ChatClient.builder(model)
                .defaultSystem("你是统一的大麦规则助手，只能基于系统给出的闭域证据回答，不得扩写不存在的规则。")
                .defaultAdvisors(
                        new SimpleLoggerAdvisor(),
                        MessageChatMemoryAdvisor.builder(chatMemory).order(MESSAGE_CHAT_MEMORY_ADVISOR_ORDER).build(),
                        AiObservabilityAdvisor.builder(observabilityService)
                                .order(OBSERVABILITY_ADVISOR_ORDER)
                                .modelName("qwen3.6-plus")
                                .requestType("UnifiedKnowledge")
                                .build()
                )
                .build();
    }

    @Bean
    public ChatClient unifiedGeneralChatClient(OpenAiChatModel model,
                                               ChatMemory chatMemory,
                                               AiObservabilityService observabilityService) {
        return ChatClient.builder(model)
                .defaultSystem("""
                        你是统一的大麦通用助手，可以回答歌手、艺人、演出背景、娱乐资讯和通用知识问题。
                        涉及实时信息、人物资料、新闻动态和事实性问题时，优先基于系统提供的联网搜索结果回答。
                        如果联网搜索未启用、搜索失败或证据不足，必须明确说明限制，不能编造来源或事实。
                        购票、下单和票档查询应建议用户切换到购票业务能力；运维诊断不能越权处理。
                        """)
                .defaultAdvisors(
                        new SimpleLoggerAdvisor(),
                        MessageChatMemoryAdvisor.builder(chatMemory).order(MESSAGE_CHAT_MEMORY_ADVISOR_ORDER).build(),
                        AiObservabilityAdvisor.builder(observabilityService)
                                .order(OBSERVABILITY_ADVISOR_ORDER)
                                .modelName("qwen3.6-plus")
                                .requestType("UnifiedGeneral")
                                .build()
                )
                .build();
    }

    @Bean
    public ChatClient unifiedOpsChatClient(OpenAiChatModel model,
                                           ChatMemory chatMemory,
                                           AiObservabilityService observabilityService) {
        return ChatClient.builder(model)
                .defaultSystem("你是统一的大麦运维助手，只能基于系统返回的日志、链路和指标证据做分析，不得编造。")
                .defaultAdvisors(
                        new SimpleLoggerAdvisor(),
                        MessageChatMemoryAdvisor.builder(chatMemory).order(MESSAGE_CHAT_MEMORY_ADVISOR_ORDER).build(),
                        AiObservabilityAdvisor.builder(observabilityService)
                                .order(OBSERVABILITY_ADVISOR_ORDER)
                                .modelName("qwen3.6-plus")
                                .requestType("UnifiedOps")
                                .build()
                )
                .build();
    }
}
