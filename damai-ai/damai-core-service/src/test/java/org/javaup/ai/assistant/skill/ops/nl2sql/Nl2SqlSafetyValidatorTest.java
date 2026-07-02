package org.javaup.ai.assistant.skill.ops.nl2sql;

import org.javaup.ai.cache.CacheManager;
import org.javaup.ai.service.Nl2SqlSemanticCatalogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class Nl2SqlSafetyValidatorTest {

    private Nl2SqlSafetyValidator validator;

    @BeforeEach
    void setUp() {
        Nl2SqlProperties properties = new Nl2SqlProperties();
        Nl2SqlSemanticCatalogService catalogService = mock(Nl2SqlSemanticCatalogService.class);
        when(catalogService.activeSnapshot()).thenReturn(Nl2SqlTestCatalog.snapshot());
        validator = new Nl2SqlSafetyValidator(properties,
                new Nl2SqlSchemaService(properties, mock(CacheManager.class), catalogService));
    }

    @Test
    void shouldAllowSelectFromWhitelistedViewAndAppendLimit() {
        Nl2SqlValidatedSql result = validator.validate("select stat_date, order_count from v_order_daily_summary");

        assertEquals("select stat_date, order_count from v_order_daily_summary limit 100", result.sql());
        assertEquals("v_order_daily_summary", result.tables().get(0));
    }

    @Test
    void shouldCapExistingLimit() {
        Nl2SqlValidatedSql result = validator.validate("select stat_date from v_order_daily_summary limit 1000");

        assertEquals("select stat_date from v_order_daily_summary limit 100", result.sql());
    }

    @Test
    void shouldAllowAiTokenCostMetrics() {
        Nl2SqlValidatedSql result = validator.validate("select stat_time, input_tokens, total_tokens from v_ai_usage_cost limit 20");

        assertEquals("select stat_time, input_tokens, total_tokens from v_ai_usage_cost limit 20", result.sql());
        assertTrue(result.tables().contains("v_ai_usage_cost"));
    }

    @Test
    void shouldAllowAggregateAliasInOrderBy() {
        Nl2SqlValidatedSql result = validator.validate("""
                select program_name, sum(failure_count) as failure_count
                from v_order_failure_summary
                group by program_name
                order by failure_count desc
                limit 10
                """);

        assertTrue(result.sql().contains("order by failure_count desc"));
    }

    @Test
    void shouldRejectDml() {
        assertThrows(Nl2SqlException.class, () -> validator.validate("update v_order_daily_summary set order_count = 1"));
    }

    @Test
    void shouldRejectMultipleStatements() {
        assertThrows(Nl2SqlException.class, () -> validator.validate("select stat_date from v_order_daily_summary; drop table d_order"));
    }

    @Test
    void shouldRejectPhysicalShardTables() {
        assertThrows(Nl2SqlException.class, () -> validator.validate("select id from d_order_0 limit 10"));
    }

    @Test
    void shouldRejectSensitiveColumns() {
        assertThrows(Nl2SqlException.class, () -> validator.validate("select mobile from v_order_daily_summary limit 10"));
    }

    @Test
    void shouldRejectSelectStar() {
        assertThrows(Nl2SqlException.class, () -> validator.validate("select * from v_order_daily_summary limit 10"));
    }

    @Test
    void shouldRejectUnknownColumnByAstWhitelist() {
        assertThrows(Nl2SqlException.class, () ->
                validator.validate("select stat_date, not_allowed_column from v_order_daily_summary limit 10"));
    }

    @Test
    void shouldRejectJoinByDefault() {
        assertThrows(Nl2SqlException.class, () ->
                validator.validate("select a.stat_date, b.program_name from v_order_daily_summary a join v_program_sales b on a.stat_date = b.show_date limit 10"));
    }

    @Test
    void shouldRejectUnionByDefault() {
        assertThrows(Nl2SqlException.class, () ->
                validator.validate("select stat_date from v_order_daily_summary union select show_date from v_program_sales"));
    }

    @Test
    void shouldRejectSubqueryByDefault() {
        assertThrows(Nl2SqlException.class, () ->
                validator.validate("select stat_date from v_order_daily_summary where order_count > (select avg(order_count) from v_order_daily_summary)"));
    }

    @Test
    void shouldRejectFunctionOutsideAllowlist() {
        assertThrows(Nl2SqlException.class, () ->
                validator.validate("select md5(program_name) from v_program_sales limit 10"));
    }
}
