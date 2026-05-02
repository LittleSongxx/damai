package org.javaup.ai.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RerankServiceTest {

    private final RerankService rerankService = new RerankService();

    @Test
    void shouldOrderDocumentsByKeywordOverlap() {
        List<Document> documents = List.of(
                new Document("支持退票规则"),
                new Document("实名观演规则"),
                new Document("退票退款取消订单说明")
        );

        List<Document> result = rerankService.rerank("退票 退款", documents, 2);

        assertEquals(2, result.size());
        assertTrue(result.get(0).getText().contains("退票退款"));
    }

    @Test
    void shouldFallbackToOriginalTopKWhenLlmRerankFails() {
        ChatClient chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        when(chatClient.prompt().user(anyString()).call().content()).thenThrow(new RuntimeException("boom"));
        List<Document> documents = List.of(
                new Document("第一条规则"),
                new Document("第二条规则")
        );

        List<Document> result = rerankService.rerankWithLLM("退票", documents, chatClient, 1);

        assertEquals(1, result.size());
        assertEquals("第一条规则", result.get(0).getText());
    }
}
