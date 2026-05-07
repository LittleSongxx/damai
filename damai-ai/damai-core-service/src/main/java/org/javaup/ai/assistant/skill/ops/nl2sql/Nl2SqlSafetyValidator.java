package org.javaup.ai.assistant.skill.ops.nl2sql;

import lombok.RequiredArgsConstructor;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class Nl2SqlSafetyValidator {

    private static final Pattern SIMPLE_LIMIT = Pattern.compile("(?is)\\blimit\\s+(\\d+)\\s*$");
    private static final Pattern OFFSET_LIMIT = Pattern.compile("(?is)\\blimit\\s+(\\d+)\\s*,\\s*(\\d+)\\s*$");
    private static final Pattern SELECT_STAR = Pattern.compile("(?is)\\bselect\\s+\\*|,\\s*\\*|\\.\\*");

    private final Nl2SqlProperties properties;
    private final Nl2SqlSchemaService schemaService;

    public Nl2SqlValidatedSql validate(String sql) {
        if (!StringUtils.hasText(sql)) {
            throw new Nl2SqlException("SQL 为空");
        }
        String stripped = stripTrailingSemicolon(sql.trim());
        rejectRawPatterns(stripped);
        Statement statement = parseSingleStatement(stripped);
        if (!(statement instanceof Select select)) {
            throw new Nl2SqlException("NL2SQL 只允许生成 SELECT 查询");
        }
        List<String> tables = referencedTables(select);
        validateTables(tables);
        validateLimitlessExposure(stripped);
        String limitedSql = enforceLimit(stripped, properties.getMaxRows());
        return new Nl2SqlValidatedSql(limitedSql, tables);
    }

    private Statement parseSingleStatement(String sql) {
        try {
            Statements statements = CCJSqlParserUtil.parseStatements(sql);
            if (statements.getStatements().size() != 1) {
                throw new Nl2SqlException("NL2SQL 只允许单条 SQL");
            }
            return statements.getStatements().get(0);
        } catch (JSQLParserException ex) {
            throw new Nl2SqlException("SQL 语法解析失败: " + ex.getMessage(), ex);
        }
    }

    private List<String> referencedTables(Select select) {
        TablesNamesFinder finder = new TablesNamesFinder();
        Set<String> tables = new LinkedHashSet<>();
        for (String table : finder.getTableList(select)) {
            String normalized = normalizeIdentifier(table);
            if (StringUtils.hasText(normalized)) {
                tables.add(normalized);
            }
        }
        return List.copyOf(tables);
    }

    private void validateTables(List<String> tables) {
        if (tables.isEmpty()) {
            throw new Nl2SqlException("SQL 未引用任何受控数据表或视图");
        }
        Set<String> allowed = new LinkedHashSet<>(schemaService.allowedTableNames());
        for (String table : tables) {
            if (table.matches(".*_\\d+$")) {
                throw new Nl2SqlException("禁止访问物理分片表: " + table);
            }
            if (!allowed.contains(table)) {
                throw new Nl2SqlException("表或视图不在 NL2SQL 白名单内: " + table);
            }
        }
    }

    private void validateLimitlessExposure(String sql) {
        if (SELECT_STAR.matcher(sql).find()) {
            throw new Nl2SqlException("禁止使用 select * 或 table.*，请显式选择允许的字段");
        }
    }

    private void rejectRawPatterns(String sql) {
        String lower = sql.toLowerCase(Locale.ROOT);
        if (lower.contains("--") || lower.contains("/*") || lower.contains("*/") || lower.matches("(?s).*\\n\\s*#.*")) {
            throw new Nl2SqlException("禁止在 NL2SQL 中使用 SQL 注释");
        }
        for (String column : properties.getSensitiveColumns()) {
            if (containsSqlToken(lower, column.toLowerCase(Locale.ROOT))) {
                throw new Nl2SqlException("SQL 触达敏感字段或敏感词: " + column);
            }
        }
        for (String function : properties.getBlockedFunctions()) {
            String token = function.toLowerCase(Locale.ROOT);
            if (token.contains(" ")) {
                if (lower.contains(token)) {
                    throw new Nl2SqlException("SQL 使用了被禁止的能力: " + function);
                }
            } else if (Pattern.compile("(?i)(^|[^a-z0-9_])" + Pattern.quote(token) + "\\s*\\(").matcher(sql).find()) {
                throw new Nl2SqlException("SQL 使用了被禁止的函数: " + function);
            }
        }
    }

    private boolean containsSqlToken(String lowerSql, String token) {
        return Pattern.compile("(?i)(^|[^a-z0-9_])" + Pattern.quote(token) + "([^a-z0-9_]|$)")
                .matcher(lowerSql)
                .find();
    }

    private String enforceLimit(String sql, int maxRows) {
        int effectiveMaxRows = Math.max(1, maxRows);
        Matcher offsetMatcher = OFFSET_LIMIT.matcher(sql);
        if (offsetMatcher.find()) {
            int requestedRows = Integer.parseInt(offsetMatcher.group(2));
            if (requestedRows <= effectiveMaxRows) {
                return sql;
            }
            return offsetMatcher.replaceFirst("limit " + offsetMatcher.group(1) + ", " + effectiveMaxRows);
        }
        Matcher simpleMatcher = SIMPLE_LIMIT.matcher(sql);
        if (simpleMatcher.find()) {
            int requestedRows = Integer.parseInt(simpleMatcher.group(1));
            if (requestedRows <= effectiveMaxRows) {
                return sql;
            }
            return simpleMatcher.replaceFirst("limit " + effectiveMaxRows);
        }
        return sql + " limit " + effectiveMaxRows;
    }

    private String stripTrailingSemicolon(String sql) {
        String value = sql;
        while (value.endsWith(";")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        if (value.contains(";")) {
            throw new Nl2SqlException("NL2SQL 只允许单条 SQL");
        }
        return value;
    }

    private String normalizeIdentifier(String identifier) {
        if (identifier == null) {
            return "";
        }
        String value = identifier.replace("`", "")
                .replace("\"", "")
                .trim()
                .toLowerCase(Locale.ROOT);
        int dot = value.lastIndexOf('.');
        if (dot >= 0 && dot < value.length() - 1) {
            value = value.substring(dot + 1);
        }
        return value;
    }
}
