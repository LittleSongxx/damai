package org.javaup.ai.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class Nl2SqlMultiTurnContextService {

    private static final int MAX_HISTORY_PER_SESSION = 5;

    private final Cache<String, List<TurnEntry>> sessionHistory;

    public Nl2SqlMultiTurnContextService(
            @Value("${damai.ai.nl2sql.context-ttl-minutes:60}") long contextTtlMinutes,
            @Value("${damai.ai.nl2sql.context-max-sessions:10000}") long contextMaxSessions) {
        this.sessionHistory = Caffeine.newBuilder()
                .maximumSize(Math.max(1, contextMaxSessions))
                .expireAfterAccess(Duration.ofMinutes(Math.max(1, contextTtlMinutes)))
                .build();
    }

    public record TurnEntry(String question, String sql, String explanation) {}

    public void recordTurn(String sessionId, String question, String sql, String explanation) {
        List<TurnEntry> history = sessionHistory.get(sessionId, k -> new ArrayList<>());
        synchronized (history) {
            if (history.size() >= MAX_HISTORY_PER_SESSION) {
                history.remove(0);
            }
            history.add(new TurnEntry(question, sql, explanation));
        }
    }

    public String buildContextPrefix(String sessionId) {
        List<TurnEntry> history = sessionHistory.getIfPresent(sessionId);
        if (history == null || history.isEmpty()) {
            return "";
        }
        List<TurnEntry> snapshot;
        synchronized (history) {
            if (history.isEmpty()) {
                return "";
            }
            snapshot = new ArrayList<>(history);
        }
        StringBuilder sb = new StringBuilder("以下是之前的对话历史（用于理解上下文指代）：\n");
        for (int i = 0; i < snapshot.size(); i++) {
            TurnEntry entry = snapshot.get(i);
            sb.append(String.format("第%d轮: 问题=\"%s\", SQL=\"%s\"\n", i + 1, entry.question(), entry.sql()));
        }
        sb.append("\n请基于以上对话上下文理解当前问题中的指代和省略。\n\n");
        return sb.toString();
    }

    public void clearSession(String sessionId) {
        sessionHistory.invalidate(sessionId);
    }
}
