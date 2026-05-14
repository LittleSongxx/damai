package org.javaup.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.assistant.runtime.AssistantObservedChatService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * @program: 大麦-ai智能服务项目。 添加 阿星不是程序员 微信，添加时备注 ai 来获取项目的完整资料
 * @description: 高级查询服务 - LLM Query Rewrite、Sub-question Decomposition、HyDE
 * @author: 阿星不是程序员
 **/
@Slf4j
@Service
public class AdvancedQueryService {

    private final ChatClient chatClient;
    private final AssistantObservedChatService observedChatService;

    public AdvancedQueryService(ChatClient chatClient) {
        this(chatClient, null);
    }

    @Autowired
    public AdvancedQueryService(@Qualifier("unifiedKnowledgeChatClient") ChatClient chatClient,
                                AssistantObservedChatService observedChatService) {
        this.chatClient = chatClient;
        this.observedChatService = observedChatService;
    }

    /**
     * LLM Query Rewrite: 用 LLM 将用户口语化查询改写为更精准的检索查询。
     * 同时生成多个变体（multi-query expansion）提高召回率。
     */
    public QueryRewriteResult rewriteQuery(String originalQuery) {
        if (!StringUtils.hasText(originalQuery)) {
            return new QueryRewriteResult(originalQuery, List.of(originalQuery));
        }
        try {
            String prompt = """
                    你是大麦票务平台的搜索查询优化器。请将用户的口语化问题改写为更精准的检索查询。
                    
                    规则：
                    1. 生成1个主查询（最精准的改写）和2个变体查询（从不同角度表达同一意图）
                    2. 保留核心语义，去除口语化表达
                    3. 补充可能的同义词和相关术语
                    4. 每个查询一行，共3行，不要编号不要标点
                    
                    用户问题：%s
                    
                    输出3行查询：
                    """.formatted(originalQuery);

            String result = callObserved("KNOWLEDGE_QUERY_REWRITE", "KnowledgeQueryRewrite", prompt);

            List<String> queries = parseLines(result, 3);
            if (queries.isEmpty()) {
                return new QueryRewriteResult(originalQuery, List.of(originalQuery));
            }
            String primary = queries.get(0);
            log.info("LLM Query Rewrite: '{}' -> primary='{}', variants={}", originalQuery, primary, queries.size());
            return new QueryRewriteResult(primary, queries);
        } catch (Exception e) {
            log.warn("LLM Query Rewrite 失败，使用原始查询", e);
            return new QueryRewriteResult(originalQuery, List.of(originalQuery));
        }
    }

    /**
     * Sub-question Decomposition: 将复杂的多跳查询拆解为多个子问题。
     * 例如："鸟巢演唱会的VIP票能退吗，退票后停车券怎么办" -> ["鸟巢VIP票退票规则", "退票后停车券处理"]
     */
    public List<String> decomposeSubQuestions(String query) {
        if (!StringUtils.hasText(query)) {
            return List.of(query);
        }
        try {
            String prompt = """
                    你是大麦票务平台的问题分析器。判断用户问题是否包含多个独立子问题。
                    
                    规则：
                    1. 如果问题是单一问题，只输出这一个问题本身（1行）
                    2. 如果问题包含2-4个独立子问题，分别输出每个子问题（每行一个）
                    3. 最多拆分为4个子问题
                    4. 每个子问题应该是自包含的、可独立检索的
                    5. 不要编号，每行一个子问题
                    
                    用户问题：%s
                    
                    子问题：
                    """.formatted(query);

            String result = callObserved("KNOWLEDGE_SUB_QUESTION", "KnowledgeSubQuestion", prompt);

            List<String> subQuestions = parseLines(result, 4);
            if (subQuestions.isEmpty()) {
                return List.of(query);
            }
            log.info("Sub-question Decomposition: '{}' -> {} 个子问题", query, subQuestions.size());
            return subQuestions;
        } catch (Exception e) {
            log.warn("Sub-question Decomposition 失败，使用原始查询", e);
            return List.of(query);
        }
    }

    /**
     * HyDE (Hypothetical Document Embeddings): 让 LLM 生成一个假想的回答文档，
     * 用这个假想文档的 embedding 去做相似度检索，而不是用查询本身。
     * 这样可以缩小 query-document 之间的语义鸿沟。
     */
    public String generateHypotheticalDocument(String query) {
        if (!StringUtils.hasText(query)) {
            return query;
        }
        try {
            String prompt = """
                    你是大麦票务平台的知识库文档生成器。请根据用户的问题，生成一段可能存在于知识库中的回答文档。
                    
                    要求：
                    1. 文档内容应该像是FAQ知识库中的真实条目
                    2. 包含具体的规则、流程、条件等细节
                    3. 长度控制在100-200字
                    4. 语气正式、内容专业
                    5. 直接输出文档内容，不要加标题或前缀
                    
                    用户问题：%s
                    
                    知识库文档：
                    """.formatted(query);

            String result = callObserved("KNOWLEDGE_HYDE", "KnowledgeHyde", prompt);

            if (StringUtils.hasText(result)) {
                log.info("HyDE 假想文档生成完成，长度={}", result.length());
                return result.trim();
            }
            return query;
        } catch (Exception e) {
            log.warn("HyDE 假想文档生成失败，使用原始查询", e);
            return query;
        }
    }

    private List<String> parseLines(String text, int maxLines) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String line : text.trim().split("\\R")) {
            String cleaned = line.replaceAll("^[\\d.、)）\\-\\*]+\\s*", "").trim();
            if (StringUtils.hasText(cleaned) && cleaned.length() >= 2) {
                lines.add(cleaned);
                if (lines.size() >= maxLines) {
                    break;
                }
            }
        }
        return lines;
    }

    private String callObserved(String stageKey, String requestType, String prompt) {
        if (observedChatService == null) {
            return chatClient.prompt().user(prompt).call().content();
        }
        return observedChatService.call(chatClient, stageKey, requestType, "qwen3.6-plus", prompt);
    }

    public record QueryRewriteResult(String primaryQuery, List<String> allQueries) {
    }
}
