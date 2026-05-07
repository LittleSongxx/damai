package org.javaup.ai.assistant.skill.general;

import org.javaup.ai.assistant.AssistantRouteType;
import org.javaup.ai.assistant.AssistantSkill;
import org.javaup.ai.assistant.AssistantSkillContext;
import org.javaup.ai.assistant.AssistantSkillDescriptor;
import org.javaup.ai.assistant.AssistantSkillRiskLevel;
import org.javaup.ai.assistant.AssistantSkillResult;
import org.javaup.ai.assistant.memory.AssistantMemoryKeyService;
import org.javaup.ai.assistant.tool.AssistantToolInvoker;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class GeneralChatSkill implements AssistantSkill {

    private final ChatClient unifiedGeneralChatClient;
    private final GeneralSearchPlanner searchPlanner;
    private final WebSearchService webSearchService;
    private final AssistantToolInvoker toolInvoker;
    private final AssistantMemoryKeyService memoryKeyService;

    public GeneralChatSkill(@Qualifier("unifiedGeneralChatClient") ChatClient unifiedGeneralChatClient,
                            GeneralSearchPlanner searchPlanner,
                            WebSearchService webSearchService,
                            AssistantToolInvoker toolInvoker,
                            AssistantMemoryKeyService memoryKeyService) {
        this.unifiedGeneralChatClient = unifiedGeneralChatClient;
        this.searchPlanner = searchPlanner;
        this.webSearchService = webSearchService;
        this.toolInvoker = toolInvoker;
        this.memoryKeyService = memoryKeyService;
    }

    @Override
    public AssistantRouteType routeType() {
        return AssistantRouteType.GENERAL;
    }

    @Override
    public AssistantSkillDescriptor descriptor() {
        return AssistantSkillDescriptor.builder()
                .skillId("general.web.search")
                .name("通用联网搜索")
                .description("处理开放域问答、人物介绍、近期动态和需要联网补充的信息查询。")
                .version("1.0.0")
                .goal("处理开放域娱乐资讯、艺人资料和需要联网证据的问题。")
                .instructions("需要实时或事实核验时优先搜索；搜索失败时说明限制。")
                .routeType(AssistantRouteType.GENERAL)
                .category("general")
                .triggerKeywords(List.of("介绍", "是谁", "新闻", "近期", "搜索", "代表作", "资料"))
                .toolAllowlist(List.of("web-search"))
                .examples(List.of("介绍一下某位歌手", "最近有哪些娱乐新闻"))
                .evalCases(List.of("联网证据不足时不得编造来源"))
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
        GeneralSearchPlan searchPlan = searchPlanner.plan(context.getMessage());
        WebSearchResult searchResult = executeSearch(context, searchPlan);
        String answer = unifiedGeneralChatClient.prompt()
                .user(withContext(context, searchResult))
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, memoryKeyService.userConversationKey(context.getRun().getUserId(), context.getRun().getConversationId())))
                .call()
                .content();
        return AssistantSkillResult.builder()
                .message(answer)
                .responseSummary(answer)
                .build();
    }

    private WebSearchResult executeSearch(AssistantSkillContext context, GeneralSearchPlan searchPlan) {
        if (!searchPlan.searchRequired()) {
            return WebSearchResult.skipped("本轮未触发联网搜索");
        }
        return toolInvoker.invoke(context.getRun().getRunId(), "web-search", "web_search", Map.of(
                "query", searchPlan.query(),
                "reason", searchPlan.reason()
        ), () -> webSearchService.search(searchPlan.query()));
    }

    private String withContext(AssistantSkillContext context, WebSearchResult searchResult) {
        return """
                用户偏好画像：
                %s

                历史摘要：
                %s

                联网搜索结果：
                %s

                当前问题：
                %s
                """.formatted(userProfile(context), memorySummary(context), searchEvidence(searchResult), context.getMessage());
    }

    private String searchEvidence(WebSearchResult result) {
        if (result == null) {
            return "未执行联网搜索";
        }
        if (!result.hasDocuments()) {
            return result.getStatus() + "：" + result.getMessage();
        }
        String documents = result.getDocuments().stream()
                .limit(5)
                .map(document -> "标题：" + safe(document.getTitle()) + "\n链接：" + safe(document.getUrl()) + "\n摘要：" + safe(document.getSnippet()))
                .collect(Collectors.joining("\n---\n"));
        return "供应商：" + result.getProvider() + "\n" + documents;
    }

    private String memorySummary(AssistantSkillContext context) {
        if (context.getMemoryContext() == null || !context.getMemoryContext().present()) {
            return "无";
        }
        return context.getMemoryContext().summary();
    }

    private String userProfile(AssistantSkillContext context) {
        if (context.getUserProfileContext() == null || !context.getUserProfileContext().present()) {
            return "无";
        }
        return context.getUserProfileContext().summary() + "；偏好标签：" + context.getUserProfileContext().preferenceTagsJson();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
