package org.javaup.ai.assistant.skill.ops.nl2sql;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.metrics.BusinessMetrics;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class Nl2SqlExecutionService {

    private static final Pattern JSON_ROWS_PATTERN = Pattern.compile("\"rows_examined_per_scan\"\\s*:\\s*([0-9]+)");
    private static final Pattern JSON_COST_PATTERN = Pattern.compile("\"query_cost\"\\s*:\\s*\"?([0-9]+(?:\\.[0-9]+)?)\"?");

    private final Nl2SqlProperties properties;
    private final DataSource nl2sqlDataSource;
    private final BusinessMetrics businessMetrics;

    public boolean isConfigured() {
        return StringUtils.hasText(properties.getDatasource().getUrl());
    }

    public Nl2SqlExecutionResult execute(String sql) {
        if (!isConfigured()) {
            return Nl2SqlExecutionResult.builder()
                    .sql(sql)
                    .columns(List.of())
                    .rows(List.of())
                    .rowCount(0)
                    .truncated(false)
                    .skipped(true)
                    .skipReason("未配置 damai.ai.nl2sql.datasource.url，只生成并校验 SQL，不执行查询")
                    .costGuard(Map.of("enabled", properties.getCostGuard().isEnabled(), "status", "SKIPPED_DATASOURCE_NOT_CONFIGURED"))
                    .durationMs(0)
                    .build();
        }
        long start = System.currentTimeMillis();
        try {
            try (Connection connection = nl2sqlDataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                connection.setReadOnly(true);
                assertReadOnlyConnection(connection);
                statement.setQueryTimeout(Math.max(1, (int) Math.ceil(properties.getQueryTimeoutMs() / 1000.0D)));
                statement.setMaxRows(Math.max(1, properties.getMaxRows()) + 1);
                CostGuardReport costGuard = runCostGuard(connection, sql);
                try (ResultSet resultSet = statement.executeQuery(sql)) {
                    Nl2SqlExecutionResult result = readResult(sql, resultSet, costGuard.asMap(), start);
                    businessMetrics.recordNl2sql(true);
                    return result;
                }
            }
        } catch (Nl2SqlException ex) {
            businessMetrics.recordNl2sql(false);
            throw ex;
        } catch (SQLException ex) {
            businessMetrics.recordNl2sql(false);
            throw new Nl2SqlException("SQL 执行失败: " + ex.getMessage(), ex);
        }
    }

    private Nl2SqlExecutionResult readResult(String sql, ResultSet resultSet, Map<String, Object> costGuard, long start) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        List<String> columns = new ArrayList<>();
        for (int index = 1; index <= metaData.getColumnCount(); index++) {
            columns.add(metaData.getColumnLabel(index));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        int maxRows = Math.max(1, properties.getMaxRows());
        boolean truncated = false;
        while (resultSet.next()) {
            if (rows.size() >= maxRows) {
                truncated = true;
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (int index = 1; index <= columns.size(); index++) {
                String column = columns.get(index - 1);
                Object value = resultSet.getObject(index);
                row.put(column, maskIfSensitive(column, normalizeValue(value)));
            }
            rows.add(row);
        }
        return Nl2SqlExecutionResult.builder()
                .sql(sql)
                .columns(columns)
                .rows(rows)
                .rowCount(rows.size())
                .truncated(truncated)
                .skipped(false)
                .costGuard(costGuard)
                .durationMs(System.currentTimeMillis() - start)
                .build();
    }

    private void assertReadOnlyConnection(Connection connection) throws SQLException {
        Nl2SqlProperties.CostGuard guard = properties.getCostGuard();
        if (!guard.isRequireReadOnlyConnection()) {
            return;
        }
        if (!connection.isReadOnly()) {
            throw new Nl2SqlException("NL2SQL 执行连接必须是 readOnly=true");
        }
    }

    private CostGuardReport runCostGuard(Connection connection, String sql) throws SQLException {
        Nl2SqlProperties.CostGuard guard = properties.getCostGuard();
        if (!guard.isEnabled()) {
            return CostGuardReport.skipped("DISABLED");
        }
        try {
            CostGuardReport report = explainJson(connection, sql);
            validateCost(report, guard);
            return report;
        } catch (SQLException ex) {
            try {
                CostGuardReport report = explainTabular(connection, sql, ex.getMessage());
                validateCost(report, guard);
                return report;
            } catch (SQLException fallbackEx) {
                if (guard.isFailClosedOnExplainError()) {
                    throw new Nl2SqlException("NL2SQL EXPLAIN 成本检查失败: " + fallbackEx.getMessage(), fallbackEx);
                }
                return CostGuardReport.skipped("EXPLAIN_FAILED", fallbackEx.getMessage());
            }
        }
    }

    private CostGuardReport explainJson(Connection connection, String sql) throws SQLException {
        try (Statement explain = connection.createStatement()) {
            explain.setQueryTimeout(Math.max(1, (int) Math.ceil(properties.getCostGuard().getExplainTimeoutMs() / 1000.0D)));
            explain.setMaxRows(1);
            try (ResultSet resultSet = explain.executeQuery("EXPLAIN FORMAT=JSON " + sql)) {
                String json = resultSet.next() ? resultSet.getString(1) : "";
                long estimatedRows = maxLong(JSON_ROWS_PATTERN, json);
                double queryCost = maxDouble(JSON_COST_PATTERN, json);
                return new CostGuardReport("PASSED", "JSON", estimatedRows, queryCost, null);
            }
        }
    }

    private CostGuardReport explainTabular(Connection connection, String sql, String jsonExplainError) throws SQLException {
        try (Statement explain = connection.createStatement()) {
            explain.setQueryTimeout(Math.max(1, (int) Math.ceil(properties.getCostGuard().getExplainTimeoutMs() / 1000.0D)));
            explain.setMaxRows(100);
            long estimatedRows = 0L;
            try (ResultSet resultSet = explain.executeQuery("EXPLAIN " + sql)) {
                while (resultSet.next()) {
                    try {
                        estimatedRows += Math.max(0L, resultSet.getLong("rows"));
                    } catch (SQLException ignored) {
                        estimatedRows = -1L;
                    }
                }
            }
            return new CostGuardReport("PASSED", "TABULAR", estimatedRows, -1D, jsonExplainError);
        }
    }

    private void validateCost(CostGuardReport report, Nl2SqlProperties.CostGuard guard) {
        if (report.estimatedRows() >= 0 && report.estimatedRows() > guard.getMaxEstimatedRows()) {
            throw new Nl2SqlException("NL2SQL 查询估算扫描行数过高: " + report.estimatedRows()
                    + " > " + guard.getMaxEstimatedRows());
        }
        if (report.queryCost() >= 0 && report.queryCost() > guard.getMaxQueryCost()) {
            throw new Nl2SqlException("NL2SQL 查询估算成本过高: " + report.queryCost()
                    + " > " + guard.getMaxQueryCost());
        }
    }

    private long maxLong(Pattern pattern, String text) {
        if (!StringUtils.hasText(text)) {
            return -1L;
        }
        Matcher matcher = pattern.matcher(text);
        long max = -1L;
        while (matcher.find()) {
            max = Math.max(max, Long.parseLong(matcher.group(1)));
        }
        return max;
    }

    private double maxDouble(Pattern pattern, String text) {
        if (!StringUtils.hasText(text)) {
            return -1D;
        }
        Matcher matcher = pattern.matcher(text);
        double max = -1D;
        while (matcher.find()) {
            max = Math.max(max, Double.parseDouble(matcher.group(1)));
        }
        return max;
    }

    private record CostGuardReport(String status,
                                   String explainMode,
                                   long estimatedRows,
                                   double queryCost,
                                   String warning) {

        static CostGuardReport skipped(String reason) {
            return new CostGuardReport("SKIPPED", reason, -1L, -1D, null);
        }

        static CostGuardReport skipped(String reason, String warning) {
            return new CostGuardReport("SKIPPED", reason, -1L, -1D, warning);
        }

        Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", status);
            result.put("explainMode", explainMode);
            result.put("estimatedRows", estimatedRows);
            result.put("queryCost", queryCost);
            if (StringUtils.hasText(warning)) {
                result.put("warning", warning);
            }
            return result;
        }
    }

    private Object normalizeValue(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof java.sql.Time time) {
            return time.toLocalTime().toString();
        }
        return value;
    }

    private Object maskIfSensitive(String column, Object value) {
        if (value == null || column == null) {
            return value;
        }
        String normalized = column.toLowerCase(Locale.ROOT);
        for (String sensitive : properties.getSensitiveColumns()) {
            if (normalized.contains(sensitive.toLowerCase(Locale.ROOT))) {
                return "******";
            }
        }
        return value;
    }
}
