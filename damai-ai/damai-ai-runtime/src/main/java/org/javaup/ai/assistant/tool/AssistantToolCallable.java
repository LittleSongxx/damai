package org.javaup.ai.assistant.tool;

@FunctionalInterface
public interface AssistantToolCallable<T> {

    T call();
}
