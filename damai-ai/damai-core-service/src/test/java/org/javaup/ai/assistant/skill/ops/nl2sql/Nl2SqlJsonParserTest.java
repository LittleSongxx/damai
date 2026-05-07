package org.javaup.ai.assistant.skill.ops.nl2sql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Nl2SqlJsonParserTest {

    private final Nl2SqlJsonParser parser = new Nl2SqlJsonParser();

    @Test
    void shouldParseJsonInsideMarkdownFence() {
        Nl2SqlGenerationResult result = parser.parseGenerationResult("""
                ```json
                {
                  "needSql": true,
                  "sql": "select stat_date from v_order_daily_summary limit 10",
                  "tables": ["v_order_daily_summary"],
                  "explanation": "查询订单汇总",
                  "chartType": "line",
                  "confidence": 0.82,
                  "assumptions": ["按自然日统计"]
                }
                ```
                """);

        assertTrue(result.isNeedSql());
        assertEquals("select stat_date from v_order_daily_summary limit 10", result.getSql());
        assertEquals("v_order_daily_summary", result.getTables().get(0));
        assertEquals("line", result.getChartType());
        assertEquals(0.82D, result.getConfidence());
    }

    @Test
    void shouldRejectNonJsonResponse() {
        assertThrows(IllegalArgumentException.class, () -> parser.parseGenerationResult("select * from t"));
    }
}
