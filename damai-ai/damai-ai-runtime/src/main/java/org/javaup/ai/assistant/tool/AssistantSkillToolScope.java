package org.javaup.ai.assistant.tool;

import java.util.List;
import java.util.Optional;

public final class AssistantSkillToolScope {

    private static final ThreadLocal<Scope> CURRENT = new InheritableThreadLocal<>();

    private AssistantSkillToolScope() {
    }

    public static ScopeHandle open(String skillId, List<String> allowedTools) {
        Scope previous = CURRENT.get();
        CURRENT.set(new Scope(skillId, allowedTools == null ? List.of() : List.copyOf(allowedTools)));
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        };
    }

    public static Optional<Scope> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public record Scope(String skillId, List<String> allowedTools) {

        public boolean allows(String toolName) {
            if (allowedTools == null || allowedTools.isEmpty()) {
                return false;
            }
            for (String allowedTool : allowedTools) {
                if ("*".equals(allowedTool)) {
                    return true;
                }
                if (allowedTool != null && allowedTool.endsWith(".*")) {
                    String prefix = allowedTool.substring(0, allowedTool.length() - 1);
                    if (toolName != null && toolName.startsWith(prefix)) {
                        return true;
                    }
                }
                if (allowedTool != null && allowedTool.equals(toolName)) {
                    return true;
                }
            }
            return false;
        }
    }

    @FunctionalInterface
    public interface ScopeHandle extends AutoCloseable {

        @Override
        void close();
    }
}
