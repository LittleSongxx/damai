package org.javaup.ai.service;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.javaup.ai.rag.channel.KnowledgeRetrievalFilter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagSearchBackendServiceTest {

    @Test
    void shouldBuildValidElasticsearchBoolQueryForSparseSearch() {
        RagSearchBackendService service = new RagSearchBackendService(
                null, null, null, null, null, null, null);

        JSONObject body = service.buildSparseSearchBody("退票规则", 3,
                KnowledgeRetrievalFilter.builder()
                        .scope("ticket")
                        .documentIds(List.of("doc-1", "doc-2", "doc-1", " "))
                        .validAt(123456789L)
                        .build());

        assertEquals(12, body.getIntValue("size"));
        JSONObject query = body.getJSONObject("query");
        assertNotNull(query.getJSONObject("bool"));
        assertFalse(query.containsKey("must"));

        JSONObject boolQuery = query.getJSONObject("bool");
        JSONArray must = boolQuery.getJSONArray("must");
        JSONArray filters = boolQuery.getJSONArray("filter");
        assertEquals("退票规则", must.getJSONObject(0)
                .getJSONObject("multi_match")
                .getString("query"));
        assertNotNull(filters);
        assertTrue(filters.size() >= 3);
    }

    @Test
    void shouldGenerateStablePositiveQdrantPointId() {
        long first = DocumentIngestionService.stablePointId("ticket-refund-policy:part-123");
        long second = DocumentIngestionService.stablePointId("ticket-refund-policy:part-123");
        long other = DocumentIngestionService.stablePointId("ticket-refund-policy:part-124");

        assertEquals(first, second);
        assertTrue(first >= 0);
        assertTrue(other >= 0);
        assertTrue(first > 0xFFFFFFFFL);
        assertTrue(other > 0xFFFFFFFFL);
        assertTrue(first != other);
        assertThrows(IllegalArgumentException.class, () -> DocumentIngestionService.stablePointId(" "));
    }
}
