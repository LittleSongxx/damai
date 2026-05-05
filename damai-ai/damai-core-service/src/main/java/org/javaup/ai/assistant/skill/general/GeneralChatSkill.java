package org.javaup.ai.assistant.skill.general;

import org.javaup.ai.assistant.AssistantRouteType;
import org.javaup.ai.assistant.AssistantSkill;
import org.javaup.ai.assistant.AssistantSkillContext;
import org.javaup.ai.assistant.AssistantSkillResult;
import org.javaup.ai.assistant.memory.AssistantMemoryKeyService;
import org.javaup.ai.assistant.tool.AssistantToolInvoker;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Map;
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
