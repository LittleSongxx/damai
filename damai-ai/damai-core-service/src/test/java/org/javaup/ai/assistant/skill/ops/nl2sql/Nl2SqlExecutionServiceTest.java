package org.javaup.ai.assistant.skill.ops.nl2sql;

import org.javaup.ai.metrics.BusinessMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Nl2SqlExecutionServiceTest {

    private Nl2SqlProperties properties;
    private DataSource dataSource;
    private BusinessMetrics metrics;

    @BeforeEach
    void setUp() {
        properties = new Nl2SqlProperties();
        properties.setEnabled(true);
        properties.getDatasource().setUrl("jdbc:mysql://localhost:3306/damai_readonly");
        properties.setMaxRows(10);
        properties.getCostGuard().setMaxEstimatedRows(100);
        properties.getCostGuard().setMaxQueryCost(1000D);
        dataSource = mock(DataSource.class);
        metrics = mock(BusinessMetrics.class);
    }

    @Test
    void shouldSkipExecutionWhenDatasourceIsNotConfigured() {
        properties.getDatasource().setUrl("");
        Nl2SqlExecutionService service = new Nl2SqlExecutionService(properties, dataSource, metrics);

        Nl2SqlExecutionResult result = service.execute("select stat_date from v_order_daily_summary limit 10");

        assertTrue(result.skipped());
        assertEquals("SKIPPED_DATASOURCE_NOT_CONFIGURED", result.costGuard().get("status"));
    }

    @Test
    void shouldRunExplainBeforeReadonlyQuery() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        Statement explainStatement = mock(Statement.class);
        ResultSet explainRs = mock(ResultSet.class);
        ResultSet queryRs = mock(ResultSet.class);
        ResultSetMetaData metaData = mock(ResultSetMetaData.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isReadOnly()).thenReturn(true);
        when(connection.createStatement()).thenReturn(statement, explainStatement);
        when(explainStatement.executeQuery(anyString())).thenReturn(explainRs);
        when(explainRs.next()).thenReturn(true);
        when(explainRs.getString(1)).thenReturn("""
                {"query_block":{"cost_info":{"query_cost":"12.5"},"table":{"rows_examined_per_scan":3}}}
                """);
        when(statement.executeQuery("select stat_date from v_order_daily_summary limit 10")).thenReturn(queryRs);
        when(queryRs.getMetaData()).thenReturn(metaData);
        when(metaData.getColumnCount()).thenReturn(1);
        when(metaData.getColumnLabel(1)).thenReturn("stat_date");
        when(queryRs.next()).thenReturn(true, false);
        when(queryRs.getObject(1)).thenReturn(java.sql.Date.valueOf("2026-06-30"));
        Nl2SqlExecutionService service = new Nl2SqlExecutionService(properties, dataSource, metrics);

        Nl2SqlExecutionResult result = service.execute("select stat_date from v_order_daily_summary limit 10");

        assertFalse(result.skipped());
        assertEquals("JSON", result.costGuard().get("explainMode"));
        assertEquals(3L, result.costGuard().get("estimatedRows"));
        assertEquals(1, result.rowCount());
        verify(metrics).recordNl2sql(true);
    }

    @Test
    void shouldRejectQueryWhenExplainRowsExceedThreshold() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        Statement explainStatement = mock(Statement.class);
        ResultSet explainRs = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isReadOnly()).thenReturn(true);
        when(connection.createStatement()).thenReturn(statement, explainStatement);
        when(explainStatement.executeQuery(anyString())).thenReturn(explainRs);
        when(explainRs.next()).thenReturn(true);
        when(explainRs.getString(1)).thenReturn("""
                {"query_block":{"cost_info":{"query_cost":"12.5"},"table":{"rows_examined_per_scan":10001}}}
                """);
        Nl2SqlExecutionService service = new Nl2SqlExecutionService(properties, dataSource, metrics);

        assertThrows(Nl2SqlException.class, () ->
                service.execute("select stat_date from v_order_daily_summary limit 10"));

        verify(metrics).recordNl2sql(false);
    }

    @Test
    void shouldRejectNonReadonlyConnection() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.isReadOnly()).thenReturn(false);
        Nl2SqlExecutionService service = new Nl2SqlExecutionService(properties, dataSource, metrics);

        assertThrows(Nl2SqlException.class, () ->
                service.execute("select stat_date from v_order_daily_summary limit 10"));
    }
}
