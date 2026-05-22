package org.javaup.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.assistant.runtime.AssistantObservedChatService;
import org.javaup.ai.rag.prompt.PromptTemplateLoader;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    private final PromptTemplateLoader templateLoader;

    public AdvancedQueryService(ChatClient chatClient) {
        this(chatClient, null, null);
    }

    @Autowired
    public AdvancedQueryService(@Qualifier("unifiedKnowledgeChatClient") ChatClient chatClient,
                                AssistantObservedChatService observedChatService,
                                PromptTemplateLoader templateLoader) {
        this.chatClient = chatClient;
        this.observedChatService = observedChatService;
        this.templateLoader = templateLoader;
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
            String prompt;
            if (templateLoader != null && templateLoader.hasTemplate("rewrite-query.st")) {
                prompt = templateLoader.render("rewrite-query.st", Map.of("user_question", originalQuery));
            } else {
                prompt = """
                    你是大麦票务平台的搜索查询优化器。请将用户的口语化问题改写为更精准的检索查询。

                    规则：
                    1. 生成1个主查询（最精准的改写）和2个变体查询（从不同角度表达同一意图）
                    2. 保留核心语义，去除口语化表达
                    3. 补充可能的同义词和相关术语
                    4. 每个查询一行，共3行，不要编号不要标点

                    用户问题：%s

                    输出3行查询：
                    """.formatted(originalQuery);
            }

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
    /**
     * Sub-question Decomposition with conservative pre-check.
     * Only invokes LLM decomposition when clear multi-question indicators exist.
     */
    public List<String> decomposeSubQuestions(String query) {
        if (!StringUtils.hasText(query)) {
            return List.of(query);
        }
        if (!isExplicitMultiQuestion(query)) {
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
     * Multi-question detection: detects queries that likely contain multiple independent questions.
     * Relaxed from the previous conservative version to improve recall for complex queries.
     */
    private boolean isExplicitMultiQuestion(String query) {
        long questionMarkCount = query.chars().filter(c -> c == '?' || c == '？').count();
        if (questionMarkCount >= 2) return true;
        if (query.matches(".*[（(]?[1-9一二三四五六七八九十][）).、]\\s*.*[（(]?[1-9一二三四五六七八九十][）).、].*")) return true;
        if (query.matches(".*第[一二三四五六七八九十].*第[一二三四五六七八九十].*")) return true;
        if (query.contains("分别") || query.contains("各自的") || query.contains("同时")
                || query.contains("以及") || query.contains("还有") || query.contains("另外")) return true;
        long separatorCount = query.chars().filter(c -> c == '；' || c == ';').count();
        if (separatorCount >= 2 && query.length() > 30) return true;
        // Relaxed: also decompose when query has multiple conjunctions suggesting multiple aspects
        if (query.length() > 25) {
            long conjCount = query.chars().filter(c -> c == '和' || c == '与').count();
            if (conjCount >= 2) return true;
        }
        return false;
    }

    /**
     * Entity linking: expand query with domain entity aliases.
     * Maps common abbreviations and aliases to canonical forms.
     */
    private static final Map<String, String> ENTITY_ALIASES = Map.ofEntries(
            Map.entry("工体", "工人体育场"),
            Map.entry("鸟巢", "国家体育场"),
            Map.entry("五棵松", "凯迪拉克中心"),
            Map.entry("VIP", "贵宾票 内场票"),
            Map.entry("身份证", "证件 实名 观演人信息"),
            Map.entry("二维码", "电子票 数字票 入场凭证"),
            Map.entry("退钱", "退款 退票 原路退回"),
            Map.entry("抢票", "购票 下单 开票 候补"),
            Map.entry("黄牛", "非官方渠道 转售 加价"),
            Map.entry("连座", "连座 相邻座位 选座"),
            Map.entry("花呗", "花呗分期 信用支付"),
            Map.entry("取票", "现场取票 自助取票 换票")
    );

    /**
     * Expand query with entity aliases for better recall.
     * Appends known aliases without changing the original query semantics.
     * Capped at 3 expansions and 200 chars total to avoid BM25 signal dilution.
     */
    public String expandWithEntities(String query) {
        if (!StringUtils.hasText(query)) return query;
        StringBuilder expansions = new StringBuilder();
        int count = 0;
        for (var entry : ENTITY_ALIASES.entrySet()) {
            if (count >= 3) break;
            if (query.contains(entry.getKey())) {
                String value = entry.getValue();
                // Skip if value terms are already present in the query
                boolean alreadyPresent = true;
                for (String term : value.split("\\s+")) {
                    if (term.length() >= 2 && !query.contains(term)) {
                        alreadyPresent = false;
                        break;
                    }
                }
                if (alreadyPresent) continue;
                if (expansions.length() > 0) expansions.append(" ");
                expansions.append(value);
                count++;
            }
        }
        if (expansions.length() == 0) return query;
        String suffix = expansions.toString();
        if (query.length() + suffix.length() > 200) return query;
        String expanded = query + " " + suffix;
        log.debug("Entity expansion: '{}' -> '{}'", query, expanded);
        return expanded;
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
                    你是大麦票务平台的FAQ知识库文档生成器。请根据用户问题，生成一个高度还原真实知识库条目的回答文档。

                    要求：
                    1. 采用结构化输出：
                       - 先明确适用的规则类型（如：条件退票/不可退/取消自动退）
                       - 再给出具体操作流程（第1步→第2步→第3步）
                       - 最后说明限制条件（时间限制/证件要求/特殊情况）
                    2. 注入领域专业术语：
                       使用规范术语如：阶梯费率、强实名制、电子票二维码、原支付路径退回、
                       订单详情页、观演人信息、检票核验、缺货登记等
                    3. 覆盖多种可能场景：
                       同一问题可能涉及退款流程、时间窗口、手续费计算、优惠券退回等多个方面
                    4. 长度控制在200-500字，信息密度高（比真实FAQ稍详细以加强向量匹配）
                    5. 语气正式、内容专业，使用客服FAQ口吻
                    6. 直接输出文档内容，不要加标题或前缀

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
            // Strip prefixes: numbered bullets, markdown markers, [DOC_N] format, brackets
            String cleaned = line
                    .replaceAll("^\\[DOC[_\\s]*\\d+\\]\\s*", "")
                    .replaceAll("^[\\[\\]\\d.、)）\\-\\*#]+\\s*", "")
                    .trim();
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

    public enum QueryType {
        SEMANTIC,   // 自然语言问句，偏语义检索
        KEYWORD,    // 精确术语查询，偏关键词匹配
        MIXED       // 混合型，等权融合
    }

    public record QueryRewriteResult(String primaryQuery, List<String> allQueries, QueryType queryType) {
        public QueryRewriteResult(String primaryQuery, List<String> allQueries) {
            this(primaryQuery, allQueries, classifyQueryType(primaryQuery));
        }

        private static QueryType classifyQueryType(String query) {
            if (query == null) return QueryType.MIXED;
            // 关键词型：短查询、精确术语、带引号或大量专有名词
            boolean shortQuery = query.length() <= 12;
            boolean hasExactTerms = query.contains("退票") || query.contains("退款") || query.contains("手续费")
                    || query.contains("快递") || query.contains("电子票") || query.contains("身份证")
                    || query.contains("实名") || query.contains("VIP") || query.contains("支付")
                    || query.contains("花呗") || query.contains("座位") || query.contains("停车场");
            boolean isQuestionForm = query.endsWith("？") || query.endsWith("?")
                    || query.startsWith("如何") || query.startsWith("怎么") || query.startsWith("什么")
                    || query.startsWith("为什么") || query.contains("能不能") || query.contains("可以");
            if (shortQuery && hasExactTerms && !isQuestionForm) return QueryType.KEYWORD;
            if (isQuestionForm && query.length() > 12) return QueryType.SEMANTIC;
            return QueryType.MIXED;
        }
    }
}
