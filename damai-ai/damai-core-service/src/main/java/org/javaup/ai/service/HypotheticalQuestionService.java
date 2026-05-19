package org.javaup.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.assistant.runtime.AssistantObservedChatService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates hypothetical user questions for FAQ chunks.
 * Each chunk gets 3-5 questions that a user might ask that this chunk could answer.
 * These questions are embedded alongside the chunk to improve retrieval recall.
 */
@Slf4j
@Service
public class HypotheticalQuestionService {

    private final ChatClient chatClient;
    private final AssistantObservedChatService observedChatService;

    public HypotheticalQuestionService(ChatClient chatClient) {
        this(chatClient, null);
    }

    @Autowired
    public HypotheticalQuestionService(
            @Qualifier("unifiedKnowledgeChatClient") ChatClient chatClient,
            @Nullable AssistantObservedChatService observedChatService) {
        this.chatClient = chatClient;
        this.observedChatService = observedChatService;
    }

    /**
     * Generate 3-5 hypothetical user questions for a given FAQ chunk.
     */
    public List<String> generateQuestions(String chunkText) {
        if (!StringUtils.hasText(chunkText)) return List.of();
        try {
            String prompt = """
                    你是大麦票务平台的用户问题生成器。阅读下面的FAQ内容，生成3-5个用户可能会问的问题。

                    规则：
                    1. 每个问题应该能从FAQ内容中找到答案
                    2. 问题应该使用不同的表达方式（口语化、正式、简短、详细）
                    3. 覆盖FAQ内容中提到的不同方面
                    4. 每行一个问题，不要编号，不要标点符号结尾
                    5. 直接输出问题，不要加任何前缀

                    FAQ内容：
                    %s

                    用户可能问的问题：
                    """.formatted(chunkText);

            String result;
            if (observedChatService != null) {
                result = observedChatService.call(chatClient, "HYPOTHETICAL_Q", "HypotheticalQuestionGen",
                        "qwen-turbo-latest", prompt);
            } else {
                result = chatClient.prompt().user(prompt).call().content();
            }

            if (!StringUtils.hasText(result)) return List.of();

            List<String> questions = new ArrayList<>();
            for (String line : result.trim().split("\\R")) {
                String cleaned = line.replaceAll("^[\\d.、)\\->\\s]+", "").trim();
                if (cleaned.length() >= 5) {
                    questions.add(cleaned);
                    if (questions.size() >= 5) break;
                }
            }
            log.debug("Generated {} hypothetical questions", questions.size());
            return questions;
        } catch (Exception e) {
            log.warn("Hypothetical question generation failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * Generate a summary for a chunk (used for summary-level retrieval).
     */
    public String generateSummary(String chunkText) {
        if (!StringUtils.hasText(chunkText)) return "";
        try {
            String prompt = """
                    用一句话总结以下FAQ内容的核心信息（不超过50字）：

                    %s

                    总结：
                    """.formatted(chunkText.length() > 1500 ? chunkText.substring(0, 1500) : chunkText);

            String result;
            if (observedChatService != null) {
                result = observedChatService.call(chatClient, "CHUNK_SUMMARY", "ChunkSummary",
                        "qwen-turbo-latest", prompt);
            } else {
                result = chatClient.prompt().user(prompt).call().content();
            }
            return result != null ? result.trim() : "";
        } catch (Exception e) {
            log.warn("Chunk summary generation failed: {}", e.getMessage());
            return "";
        }
    }
}
