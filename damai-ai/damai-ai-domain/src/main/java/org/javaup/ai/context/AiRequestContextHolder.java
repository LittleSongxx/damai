package org.javaup.ai.context;

import java.util.Optional;

/**
 * ThreadLocal 请求上下文。
 */
public final class AiRequestContextHolder {

    private static final ThreadLocal<AiRequestContext> HOLDER = new InheritableThreadLocal<>();

    private AiRequestContextHolder() {
    }

    public static void set(AiRequestContext context) {
        HOLDER.set(context);
    }

    public static AiRequestContext get() {
        return HOLDER.get();
    }

    public static Optional<AiRequestContext> getOptional() {
        return Optional.ofNullable(HOLDER.get());
    }

    public static AiUserContext getRequiredUser() {
        AiRequestContext context = HOLDER.get();
        if (context == null || context.getUser() == null) {
            throw new IllegalStateException("AI user context is missing");
        }
        return context.getUser();
    }

    public static void enrich(String conversationId, String runId, String requestType) {
        AiRequestContext current = HOLDER.get();
        AiUserContext user = current == null ? null : current.getUser();
        HOLDER.set(AiRequestContext.builder()
                .user(user)
                .conversationId(conversationId)
                .runId(runId)
                .requestType(requestType)
                .build());
    }

    public static void clear() {
        HOLDER.remove();
    }
}
