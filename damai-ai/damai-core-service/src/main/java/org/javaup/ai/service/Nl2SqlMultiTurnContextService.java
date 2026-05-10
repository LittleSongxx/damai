package org.javaup.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class Nl2SqlMultiTurnContextService {

    private static final int MAX_HISTORY_PER_SESSION = 5;

    private final Map<String, List<TurnEntry>> sessionHistory = new ConcurrentHashMap<>();

    public record TurnEntry(String question, String sql, String explanation) {}

    public void recordTurn(String sessionId, String question, String sql, String explanation) {
        sessionHistory.computeIfAbsent(sessionId, k -> new ArrayList<>());
        List<TurnEntry> history = sessionHistory.get(sessionId);
        if (history.size() >= MAX_HISTORY_PER_SESSION) {
            history.remove(0);
        }
        history.add(new TurnEntry(question, sql, explanation));
    }

    public String buildContextPrefix(String sessionId) {
        List<TurnEntry> history = sessionHistory.get(sessionId);
        if (history == null || history.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("以下是之前的对话历史（用于理解上下文指代）：\n");
        for (int i = 0; i < history.size(); i++) {
            TurnEntry entry = history.get(i);
            sb.append(String.format("第%d轮: 问题=\"%s\", SQL=\"%s\"\n", i + 1, entry.question(), entry.sql()));
        }
        sb.append("\n请基于以上对话上下文理解当前问题中的指代和省略。\n\n");
        return sb.toString();
    }

    public void clearSession(String sessionId) {
        sessionHistory.remove(sessionId);
    }
}
