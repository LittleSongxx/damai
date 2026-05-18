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

@Slf4j
@Service
@RequiredArgsConstructor
public class Nl2SqlExecutionService {

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
                    .durationMs(0)
                    .build();
        }
        long start = System.currentTimeMillis();
        try {
            try (Connection connection = nl2sqlDataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                connection.setReadOnly(true);
                statement.setQueryTimeout(Math.max(1, (int) Math.ceil(properties.getQueryTimeoutMs() / 1000.0D)));
                statement.setMaxRows(Math.max(1, properties.getMaxRows()) + 1);
                try (ResultSet resultSet = statement.executeQuery(sql)) {
                    Nl2SqlExecutionResult result = readResult(sql, resultSet, start);
                    businessMetrics.recordNl2sql(true);
                    return result;
                }
            }
        } catch (SQLException ex) {
            businessMetrics.recordNl2sql(false);
            throw new Nl2SqlException("SQL 执行失败: " + ex.getMessage(), ex);
        }
    }

    private Nl2SqlExecutionResult readResult(String sql, ResultSet resultSet, long start) throws SQLException {
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
                .durationMs(System.currentTimeMillis() - start)
                .build();
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
