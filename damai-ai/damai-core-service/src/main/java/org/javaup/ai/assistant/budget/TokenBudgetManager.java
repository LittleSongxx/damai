package org.javaup.ai.assistant.budget;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prompt token 预算管理 —— 遵循 Dify 的多级窗口管理与 Anthropic "Context Engineering" 设计。
 *
 * <p>Dify 核心做法: 将上下文窗口切分为系统提示、对话历史、用户查询、上下文文档等
 * 独立预算区间，每个区间有配额上限，超出时触发截断或降级。
 *
 * <p>Anthropic "Context Engineering": "Be deliberate about what goes into the context
 * window. Every token has a cost — both in latency and in attention dilution."
 *
 * <h3>模型上下文窗口默认值（保守估计）</h3>
 * <ul>
 *   <li>qwen3.6-plus: 32,768 tokens</li>
 *   <li>deepseek-chat: 32,768 tokens</li>
 *   <li>qwen-turbo-latest: 8,192 tokens</li>
 *   <li>deepseek-r1:7b: 32,768 tokens</li>
 *   <li>其他: 16,384 tokens</li>
 * </ul>
 */
@Slf4j
@Service
public class TokenBudgetManager {

    private static final int DEFAULT_CONTEXT_WINDOW = 16384;
    private static final int DEFAULT_RESPONSE_RESERVE = 2048;

    /**
     * 模型上下文窗口大小（保守估计）。
     *
     * <p>参考 Dify 的模型配置表，以保守值避免超出。生产环境可通过配置中心动态调整。
     */
    private static final Map<String, Integer> MODEL_CONTEXT_WINDOWS = Map.of(
            "qwen3.6-plus", 32768,
            "qwen-max", 32768,
            "qwen-plus", 32768,
            "qwen-turbo-latest", 8192,
            "deepseek-chat", 32768,
            "deepseek-r1:7b", 32768,
            "deepseek-reasoner", 65536
    );

    /**
     * 响应预留 token 数，确保模型有足够空间生成回答。
     * 遵循 Dify 默认的 2048 token 响应预留。
     */
    private static final Map<String, Integer> MODEL_RESPONSE_RESERVES = Map.of(
            "deepseek-reasoner", 8192
    );

    private final ConcurrentHashMap<String, TokenBudget> budgets = new ConcurrentHashMap<>();

    /**
     * 估算文本的 token 数量。
     *
     * <p>使用启发式算法：CJK 字符每个约占 1.5 tokens（Qwen/cl100k_base 词表均值），
     * 非 CJK 字符每 4 个约占 1 token。结果含 5% 安全余量（上取整）。
     *
     * <p>注: 生产环境可替换为模型原生 tokenizer（如通过 HTTP 调用 tokenizer 服务），
     * 当前实现遵循 Dify 的离线近似估算策略。
     */
    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjkCount = 0;
        int nonCjkCount = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                cjkCount++;
            } else {
                nonCjkCount++;
            }
        }
        double estimated = (cjkCount / 1.5) + (nonCjkCount / 4.0);
        int withMargin = (int) Math.ceil(estimated * 1.05);
        return Math.max(1, withMargin);
    }

    /**
     * 为 Run 创建 token 预算，切分为标准类别。
     *
     * <p>预算分配比例遵循 Dify 的设计:
     * <ul>
     *   <li>SYSTEM_PROMPT: 10% — 技能指令、系统提示</li>
     *   <li>MEMORY: 15% — 对话历史摘要</li>
     *   <li>USER_PROFILE: 5% — 用户画像</li>
     *   <li>RETRIEVAL_EVIDENCE: 40% — 检索证据（知识库 Skill 主要消耗）</li>
     *   <li>USER_MESSAGE: 10% — 用户当前消息</li>
     *   <li>RESPONSE: 由 responseReserve 覆盖</li>
     * </ul>
     */
    public TokenBudget createBudget(String modelName) {
        int contextWindow = MODEL_CONTEXT_WINDOWS.getOrDefault(modelName, DEFAULT_CONTEXT_WINDOW);
        int responseReserve = MODEL_RESPONSE_RESERVES.getOrDefault(modelName, DEFAULT_RESPONSE_RESERVE);
        TokenBudget budget = new TokenBudget(modelName, contextWindow, responseReserve);

        int allocatable = contextWindow - responseReserve;
        budget.allocate("SYSTEM_PROMPT", (int) (allocatable * 0.10));
        budget.allocate("MEMORY", (int) (allocatable * 0.15));
        budget.allocate("USER_PROFILE", (int) (allocatable * 0.05));
        budget.allocate("RETRIEVAL_EVIDENCE", (int) (allocatable * 0.40));
        budget.allocate("USER_MESSAGE", (int) (allocatable * 0.10));

        return budget;
    }

    /**
     * 验证证据是否超出检索配额，超出时返回建议截断长度。
     *
     * @return null 表示不超预算；非 null 表示建议截断到的字符数
     */
    public Integer checkEvidenceBudget(TokenBudget budget, String evidenceText) {
        int evidenceTokens = estimateTokens(evidenceText);
        int allocated = budget.getAllocatedFor("RETRIEVAL_EVIDENCE");
        int used = budget.getUsedFor("RETRIEVAL_EVIDENCE");
        int remaining = allocated - used;
        if (evidenceTokens <= remaining) {
            return null;
        }
        int allowedChars = (int) (remaining * 1.5);
        log.warn("Evidence exceeds retrieval budget: {} tokens > {} remaining, suggest truncating to ~{} chars",
                evidenceTokens, remaining, allowedChars);
        return Math.max(100, allowedChars);
    }

    /**
     * 检查完整提示是否接近上下文窗口上限。
     */
    public void verifyBudget(TokenBudget budget) {
        if (budget.isBudgetExhausted()) {
            log.error("Token budget EXHAUSTED: {}", budget.getDetailMessage());
        } else if (budget.isApproachingLimit()) {
            log.warn("{}", budget.getWarningMessage());
        }
    }

    /**
     * 获取模型上下文窗口大小（保守值）。
     */
    public int getContextWindow(String modelName) {
        return MODEL_CONTEXT_WINDOWS.getOrDefault(modelName, DEFAULT_CONTEXT_WINDOW);
    }

    // ------- 启发式 token 计数辅助方法 -------

    private boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_C
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_D
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_E
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_F
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS_SUPPLEMENT
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES
                || block == Character.UnicodeBlock.HANGUL_JAMO
                || block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO;
    }
}
