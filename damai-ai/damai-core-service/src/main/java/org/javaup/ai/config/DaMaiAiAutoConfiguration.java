package org.javaup.ai.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.javaup.ai.ai.rag.MarkdownLoader;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.support.ResourcePatternResolver;

public class DaMaiAiAutoConfiguration {

    @Bean
    public ChatClient chatClient(OpenAiChatModel model) {
        return ChatClient.builder(model)
                .defaultSystem("你是一位智能助手，你的特点是温柔、善良，你的名字叫智能小艾，要结合你的特点积极的回答用户的问题。")
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
    }

    @Bean
    public ChatClient titleChatClient(OpenAiChatModel model) {
        return ChatClient.builder(model)
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .build();
    }

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(20)
                .build();
    }

    @Bean
    public VectorStore vectorStore(OpenAiEmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }

    @Bean
    public MarkdownLoader markdownLoader(ResourcePatternResolver resourcePatternResolver,
                                         @Value("${damai.ai.rag.document-pattern:classpath:datum/*.md}") String documentPattern,
                                         @Value("${damai.ai.rag.chunk-size:400}") int chunkSize,
                                         @Value("${damai.ai.rag.min-chunk-size-chars:50}") int minChunkSizeChars,
                                         @Value("${damai.ai.rag.min-chunk-length-to-embed:5}") int minChunkLengthToEmbed,
                                         @Value("${damai.ai.rag.max-num-chunks:10000}") int maxNumChunks,
                                         @Value("${damai.ai.rag.min-doc-length-for-token-split:1000}") int minDocLengthForTokenSplit) {
        return new MarkdownLoader(resourcePatternResolver, documentPattern, chunkSize,
                minChunkSizeChars, minChunkLengthToEmbed, maxNumChunks, minDocLengthForTokenSplit);
    }

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        return CircuitBreakerRegistry.ofDefaults();
    }
}
