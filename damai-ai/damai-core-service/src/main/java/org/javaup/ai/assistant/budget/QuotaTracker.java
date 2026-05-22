package org.javaup.ai.assistant.budget;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 生产配额实时追踪器 —— 遵循 Dify 的 Quota 管理体系。
 *
 * <p>Dify 的做法: 在内存中实时追踪每个用户/租户的 token 消耗，
 * 定期同步到持久化存储，配额接近上限时触发警告和流式中止。
 *
 * <p>当前实现: 基于内存的实时计数（ConcurrentHashMap + AtomicLong），
 * 按天分区，每次 LLM 调用后更新，支持流式过程中的实时检查。
 *
 * <p>相比旧版 isDailyBudgetExceeded (每次查 DB):
 * <ul>
 *   <li>延迟: ~0μs vs ~5ms (DB 查询)</li>
 *   <li>精度: 实时 vs 写入后可见</li>
 *   <li>流式中止: 支持 vs 不支持</li>
 * </ul>
 */
@Slf4j
@Service
public class QuotaTracker {

    private final ConcurrentHashMap<String, AtomicLong> dailyUsage = new ConcurrentHashMap<>();

    /**
     * 记录一次 LLM 调用的 token 消耗。线程安全，无锁 CAS。
     */
    public void recordUsage(long userId, long tokens) {
        String key = todayKey(userId);
        dailyUsage.computeIfAbsent(key, k -> new AtomicLong()).addAndGet(tokens);
    }

    /**
     * 获取用户今日已消耗的 token 数。
     */
    public long getTodayUsage(long userId) {
        String key = todayKey(userId);
        AtomicLong usage = dailyUsage.get(key);
        return usage != null ? usage.get() : 0;
    }

    /**
     * 检查是否超出预算。实时，无需 DB 查询。
     *
     * @return true 表示预算已耗尽
     */
    public boolean isBudgetExhausted(long userId, long dailyBudget) {
        if (dailyBudget <= 0) return false;
        return getTodayUsage(userId) >= dailyBudget;
    }

    /**
     * 检查是否接近预算上限（已使用 >= 85%）。
     * 用于提前触发流式中止的软性检查。
     */
    public boolean isBudgetNearLimit(long userId, long dailyBudget, double threshold) {
        if (dailyBudget <= 0) return false;
        return (double) getTodayUsage(userId) / dailyBudget >= threshold;
    }

    /**
     * 获取剩余可用 token 数。
     */
    public long getRemainingTokens(long userId, long dailyBudget) {
        if (dailyBudget <= 0) return Long.MAX_VALUE;
        return Math.max(0, dailyBudget - getTodayUsage(userId));
    }

    /**
     * 为新的一天清理旧计数器（由每日 cron 或首次请求触发）。
     */
    public void evictStaleEntries() {
        String today = LocalDate.now().toString();
        dailyUsage.keySet().removeIf(key -> !key.startsWith(today));
    }

    private String todayKey(long userId) {
        return LocalDate.now() + ":" + userId;
    }
}
