package org.javaup.ai.assistant.tool;

import org.javaup.ai.assistant.AssistantSkillContext;
import org.javaup.ai.assistant.AssistantSkillRegistry;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

@Configuration
public class AssistantToolCallbacks {

    @Bean
    public ToolCallback searchKnowledgeCallback(@Lazy AssistantSkillRegistry skillRegistry) {
        return FunctionToolCallback.<String, String>builder("searchKnowledge", query -> {
                    var skill = skillRegistry.getRequired("knowledge.policy.qa");
                    var ctx = AssistantSkillContext.builder().message(query).build();
                    var result = skill.execute(ctx);
                    return result.getResponseSummary() != null ? result.getResponseSummary() : "knowledge search completed";
                })
                .inputType(String.class)
                .description("Search the knowledge base for ticketing rules, refund policies, venue guides, and FAQ information. Input is a search query string.")
                .build();
    }

    @Bean
    public ToolCallback queryDataCallback(@Lazy AssistantSkillRegistry skillRegistry) {
        return FunctionToolCallback.<String, String>builder("queryData", query -> {
                    var skill = skillRegistry.getRequired("ops.nl2sql.query");
                    var ctx = AssistantSkillContext.builder().message(query).build();
                    var result = skill.execute(ctx);
                    return result.getResponseSummary() != null ? result.getResponseSummary() : "data query completed";
                })
                .inputType(String.class)
                .description("Query structured operational data such as order counts, payment success rates, and revenue metrics. Input is a natural language query about data.")
                .build();
    }

    @Bean
    public ToolCallback generalChatCallback() {
        return FunctionToolCallback.<String, String>builder("generalChat", query -> {
                    return "general chat response for: " + query;
                })
                .inputType(String.class)
                .description("Handle general conversation and questions that don't require specific knowledge retrieval or data queries.")
                .build();
    }
}
