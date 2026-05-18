package org.javaup.ai.assistant.skill.ops.nl2sql;

import org.springframework.stereotype.Component;

@Component
public class Nl2SqlErrorClassifier {

    public enum ErrorCategory {
        SYNTAX_ERROR,
        TABLE_NOT_FOUND,
        COLUMN_NOT_FOUND,
        JOIN_ERROR,
        FILTER_ERROR,
        AGGREGATION_ERROR,
        SUBQUERY_ERROR,
        TYPE_MISMATCH,
        UNKNOWN
    }

    public ErrorCategory classify(String errorMessage) {
        if (errorMessage == null) {
            return ErrorCategory.UNKNOWN;
        }
        String lower = errorMessage.toLowerCase();

        if (lower.contains("syntax") || lower.contains("parse") || lower.contains("unexpected token")
                || lower.contains("sql 语法") || lower.contains("解析失败")) {
            return ErrorCategory.SYNTAX_ERROR;
        }
        if (lower.contains("doesn't exist") || lower.contains("does not exist")
                || lower.contains("not found") || lower.contains("不存在")
                || lower.contains("unknown table") || lower.contains("table")) {
            return ErrorCategory.TABLE_NOT_FOUND;
        }
        if (lower.contains("unknown column") || lower.contains("column")
                || lower.contains("字段") || lower.contains("列")) {
            return ErrorCategory.COLUMN_NOT_FOUND;
        }
        if (lower.contains("join")) {
            return ErrorCategory.JOIN_ERROR;
        }
        if (lower.contains("where") || lower.contains("filter") || lower.contains("条件")
                || lower.contains("operand") || lower.contains("compare")) {
            return ErrorCategory.FILTER_ERROR;
        }
        if (lower.contains("group") || lower.contains("aggregate") || lower.contains("sum")
                || lower.contains("count") || lower.contains("avg") || lower.contains("having")
                || lower.contains("聚合")) {
            return ErrorCategory.AGGREGATION_ERROR;
        }
        if (lower.contains("subquery") || lower.contains("子查询") || lower.contains("derived")) {
            return ErrorCategory.SUBQUERY_ERROR;
        }
        if (lower.contains("type") || lower.contains("cast") || lower.contains("convert")
                || lower.contains("类型") || lower.contains("collation")) {
            return ErrorCategory.TYPE_MISMATCH;
        }
        return ErrorCategory.UNKNOWN;
    }

    public String buildRepairGuidance(ErrorCategory category) {
        return switch (category) {
            case SYNTAX_ERROR -> "SQL 语法错误。请检查关键字拼写、括号匹配、引号使用。使用标准 MySQL 语法。";
            case TABLE_NOT_FOUND -> "表名不存在。请确认只使用 Schema 中列出的表名，检查是否有拼写错误。";
            case COLUMN_NOT_FOUND -> "字段名不存在。请确认只使用 Schema 中列出的字段名，检查表别名是否正确定义。";
            case JOIN_ERROR -> "JOIN 错误。请检查 JOIN 条件是否正确，关联字段是否存在。使用 Schema 中标注的外键关系。";
            case FILTER_ERROR -> "WHERE 条件错误。请检查比较运算符、值类型是否匹配，IN 列表格式是否正确。";
            case AGGREGATION_ERROR -> "聚合错误。请检查 GROUP BY 是否包含所有非聚合字段，HAVING 条件是否正确。";
            case SUBQUERY_ERROR -> "子查询错误。请检查子查询是否返回正确的列数和类型，是否存在循环引用。";
            case TYPE_MISMATCH -> "类型不匹配。请检查比较双方的数据类型是否兼容，必要时使用 CAST 或 CONVERT。";
            case UNKNOWN -> "SQL 执行失败。请重新检查语法和 Schema 兼容性。";
        };
    }
}
