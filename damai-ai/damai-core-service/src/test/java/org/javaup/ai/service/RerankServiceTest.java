package org.javaup.ai.service;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RerankServiceTest {

    @Test
    void shouldOrderDocumentsByKeywordOverlap() {
        RerankService rerankService = new RerankService();
        List<Document> documents = List.of(
                new Document("支持退票规则"),
                new Document("实名观演规则"),
                new Document("退票退款取消订单说明")
        );

        List<Document> result = rerankService.rerankByKeyword("退票 退款", documents, 2);

        assertEquals(2, result.size());
        assertTrue(result.get(0).getText().contains("退票退款"));
    }

    @Test
    void shouldFallbackToKeywordWhenDashScopeRerankFails() {
        RerankService rerankService = new RerankService();
        List<Document> documents = List.of(
                new Document("第一条规则关于退票"),
                new Document("第二条规则关于入场"),
                new Document("第三条规则关于退票退款")
        );

        // rerank() will fail on DashScope API (no apiKey), fallback to keyword
        List<Document> result = rerankService.rerank("退票", documents, 2);

        assertEquals(2, result.size());
    }
}
