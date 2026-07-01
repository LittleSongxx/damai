package org.javaup.ai.assistant.skill.ops.nl2sql;

import lombok.RequiredArgsConstructor;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.AnalyticExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectBody;
import net.sf.jsqlparser.statement.select.SelectExpressionItem;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.SubSelect;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
        validateSelectPolicy(select);
        validateColumns(select, tables);
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

    private void validateSelectPolicy(Select select) {
        if (!properties.isAllowCte() && select.getWithItemsList() != null && !select.getWithItemsList().isEmpty()) {
            throw new Nl2SqlException("当前 NL2SQL 策略禁止使用 CTE/with 查询");
        }
        SelectBody body = select.getSelectBody();
        if (body instanceof SetOperationList && !properties.isAllowSetOperations()) {
            throw new Nl2SqlException("当前 NL2SQL 策略禁止使用 UNION/INTERSECT/EXCEPT");
        }
        if (!(body instanceof PlainSelect plainSelect)) {
            throw new Nl2SqlException("当前 NL2SQL 只允许单层 SELECT 查询");
        }
        if (!properties.isAllowJoins() && plainSelect.getJoins() != null && !plainSelect.getJoins().isEmpty()) {
            throw new Nl2SqlException("当前 NL2SQL 策略禁止使用 JOIN，请优先使用受控汇总视图");
        }
        if (!properties.isAllowSubqueries() && containsSubquery(plainSelect)) {
            throw new Nl2SqlException("当前 NL2SQL 策略禁止使用子查询，请优先使用受控汇总视图");
        }
        validateExpressionPolicy(plainSelect);
    }

    private boolean containsSubquery(PlainSelect plainSelect) {
        if (plainSelect.getFromItem() instanceof SubSelect) {
            return true;
        }
        if (plainSelect.getJoins() != null && plainSelect.getJoins().stream().anyMatch(join -> join.getRightItem() instanceof SubSelect)) {
            return true;
        }
        SubqueryDetector detector = new SubqueryDetector();
        visitSelectExpressions(plainSelect, detector);
        return detector.found;
    }

    private void validateExpressionPolicy(PlainSelect plainSelect) {
        PolicyExpressionVisitor visitor = new PolicyExpressionVisitor();
        visitSelectExpressions(plainSelect, visitor);
        Set<String> allowedFunctions = new LinkedHashSet<>(properties.getAllowedFunctions().stream()
                .map(function -> function.toLowerCase(Locale.ROOT))
                .toList());
        for (String function : visitor.functions) {
            if (!allowedFunctions.contains(function)) {
                throw new Nl2SqlException("SQL 使用了未纳入白名单的函数: " + function);
            }
        }
        if (!properties.isAllowWindowFunctions() && visitor.windowFunctionUsed) {
            throw new Nl2SqlException("当前 NL2SQL 策略禁止使用窗口函数");
        }
    }

    private void validateColumns(Select select, List<String> tables) {
        if (!(select.getSelectBody() instanceof PlainSelect plainSelect)) {
            return;
        }
        Map<String, Set<String>> allowedColumns = schemaService.allowedColumnsByTable();
        Map<String, String> aliases = tableAliases(plainSelect);
        Set<String> selectAliases = selectAliases(plainSelect);
        Set<String> referencedColumns = new LinkedHashSet<>();
        ColumnCollectingVisitor visitor = new ColumnCollectingVisitor(referencedColumns);
        visitSelectExpressions(plainSelect, visitor);
        for (String rawColumn : referencedColumns) {
            String column = normalizeIdentifier(rawColumn);
            if (!StringUtils.hasText(column)) {
                continue;
            }
            String qualifier = normalizeQualifier(rawColumn);
            if (!StringUtils.hasText(qualifier) && selectAliases.contains(column)) {
                continue;
            }
            if (StringUtils.hasText(qualifier)) {
                String table = aliases.getOrDefault(qualifier, qualifier);
                Set<String> tableColumns = allowedColumns.getOrDefault(table, Set.of());
                if (!tableColumns.contains(column)) {
                    throw new Nl2SqlException("字段不在 NL2SQL 白名单内: " + rawColumn);
                }
                continue;
            }
            boolean matched = tables.stream()
                    .map(table -> allowedColumns.getOrDefault(table, Set.of()))
                    .anyMatch(columns -> columns.contains(column));
            if (!matched) {
                throw new Nl2SqlException("字段不在 NL2SQL 白名单内: " + rawColumn);
            }
        }
    }

    private Map<String, String> tableAliases(PlainSelect plainSelect) {
        Map<String, String> aliases = new LinkedHashMap<>();
        collectAlias(plainSelect.getFromItem(), aliases);
        if (plainSelect.getJoins() != null) {
            plainSelect.getJoins().forEach(join -> collectAlias(join.getRightItem(), aliases));
        }
        return aliases;
    }

    private Set<String> selectAliases(PlainSelect plainSelect) {
        Set<String> aliases = new LinkedHashSet<>();
        if (plainSelect.getSelectItems() == null) {
            return aliases;
        }
        for (SelectItem item : plainSelect.getSelectItems()) {
            if (item instanceof SelectExpressionItem expressionItem
                    && expressionItem.getAlias() != null
                    && StringUtils.hasText(expressionItem.getAlias().getName())) {
                aliases.add(normalizeIdentifier(expressionItem.getAlias().getName()));
            }
        }
        return aliases;
    }

    private void collectAlias(Object item, Map<String, String> aliases) {
        if (!(item instanceof Table table)) {
            return;
        }
        String tableName = normalizeIdentifier(table.getName());
        aliases.put(tableName, tableName);
        if (table.getAlias() != null && StringUtils.hasText(table.getAlias().getName())) {
            aliases.put(normalizeIdentifier(table.getAlias().getName()), tableName);
        }
    }

    private void visitSelectExpressions(PlainSelect plainSelect, ExpressionVisitorAdapter visitor) {
        visitSelectItems(plainSelect.getSelectItems(), visitor);
        visitExpression(plainSelect.getWhere(), visitor);
        visitExpression(plainSelect.getHaving(), visitor);
        if (plainSelect.getGroupBy() != null && plainSelect.getGroupBy().getGroupByExpressionList() != null) {
            plainSelect.getGroupBy().getGroupByExpressionList().getExpressions().forEach(expression -> visitExpression(expression, visitor));
        }
        if (plainSelect.getOrderByElements() != null) {
            plainSelect.getOrderByElements().forEach(element -> visitExpression(element.getExpression(), visitor));
        }
        if (plainSelect.getJoins() != null) {
            plainSelect.getJoins().forEach(join -> {
                visitExpression(join.getOnExpression(), visitor);
                Collection<Expression> expressions = join.getOnExpressions();
                if (expressions != null) {
                    expressions.forEach(expression -> visitExpression(expression, visitor));
                }
                if (join.getUsingColumns() != null) {
                    join.getUsingColumns().forEach(column -> column.accept(visitor));
                }
            });
        }
    }

    private void visitSelectItems(List<SelectItem> items, ExpressionVisitorAdapter visitor) {
        if (items == null) {
            return;
        }
        for (SelectItem item : items) {
            if (item instanceof AllColumns || item instanceof AllTableColumns) {
                throw new Nl2SqlException("禁止使用 select * 或 table.*，请显式选择允许的字段");
            }
            if (item instanceof SelectExpressionItem expressionItem) {
                visitExpression(expressionItem.getExpression(), visitor);
            }
        }
    }

    private void visitExpression(Expression expression, ExpressionVisitorAdapter visitor) {
        if (expression != null) {
            expression.accept(visitor);
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
        return Pattern.compile("(?i)(^|[^a-z0-9])" + Pattern.quote(token) + "([^a-z0-9]|$)")
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

    private String normalizeQualifier(String identifier) {
        if (identifier == null) {
            return "";
        }
        String value = identifier.replace("`", "")
                .replace("\"", "")
                .trim()
                .toLowerCase(Locale.ROOT);
        int dot = value.lastIndexOf('.');
        if (dot <= 0) {
            return "";
        }
        return value.substring(0, dot);
    }

    private static class PolicyExpressionVisitor extends ExpressionVisitorAdapter {
        private final Set<String> functions = new LinkedHashSet<>();
        private boolean windowFunctionUsed;

        @Override
        public void visit(Function function) {
            if (function.getName() != null) {
                functions.add(function.getName().toLowerCase(Locale.ROOT));
            }
            super.visit(function);
        }

        @Override
        public void visit(AnalyticExpression expression) {
            windowFunctionUsed = true;
            super.visit(expression);
        }
    }

    private static class SubqueryDetector extends ExpressionVisitorAdapter {
        private boolean found;

        @Override
        public void visit(SubSelect subSelect) {
            found = true;
            super.visit(subSelect);
        }
    }

    private static class ColumnCollectingVisitor extends ExpressionVisitorAdapter {
        private final Set<String> columns;

        private ColumnCollectingVisitor(Set<String> columns) {
            this.columns = columns;
        }

        @Override
        public void visit(Column column) {
            if (column != null && StringUtils.hasText(column.getFullyQualifiedName())) {
                columns.add(column.getFullyQualifiedName());
            }
            super.visit(column);
        }
    }
}
