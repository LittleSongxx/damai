package org.javaup.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.assistant.skill.ops.nl2sql.Nl2SqlProperties;
import org.javaup.ai.entity.AiNl2SqlSemanticCatalog;
import org.javaup.ai.mapper.AiNl2SqlSemanticCatalogMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class Nl2SqlSemanticCatalogService {

    private static final String DEFAULT_DATASOURCE = "damai-ai-metrics";

    private final AiNl2SqlSemanticCatalogMapper catalogMapper;

    public CatalogSnapshot activeSnapshot() {
        List<AiNl2SqlSemanticCatalog> rows = catalogMapper.selectList(Wrappers.lambdaQuery(AiNl2SqlSemanticCatalog.class)
                .eq(AiNl2SqlSemanticCatalog::getCatalogStatus, "ACTIVE")
                .eq(AiNl2SqlSemanticCatalog::getStatus, 1)
                .orderByDesc(AiNl2SqlSemanticCatalog::getVersionNo)
                .orderByAsc(AiNl2SqlSemanticCatalog::getId));
        return fromRows(rows);
    }

    public List<AiNl2SqlSemanticCatalog> activeRows() {
        return catalogMapper.selectList(Wrappers.lambdaQuery(AiNl2SqlSemanticCatalog.class)
                .eq(AiNl2SqlSemanticCatalog::getCatalogStatus, "ACTIVE")
                .eq(AiNl2SqlSemanticCatalog::getStatus, 1)
                .orderByAsc(AiNl2SqlSemanticCatalog::getSemanticType)
                .orderByAsc(AiNl2SqlSemanticCatalog::getSemanticKey));
    }

    public CatalogSnapshot reload() {
        return activeSnapshot();
    }

    private CatalogSnapshot fromRows(List<AiNl2SqlSemanticCatalog> rows) {
        Map<String, Nl2SqlProperties.Table> tables = new LinkedHashMap<>();
        List<Nl2SqlProperties.Term> terms = new ArrayList<>();
        List<Nl2SqlProperties.Example> examples = new ArrayList<>();
        int version = 0;
        for (AiNl2SqlSemanticCatalog row : rows) {
            version = Math.max(version, row.getVersionNo() == null ? 0 : row.getVersionNo());
            String type = normalize(row.getSemanticType());
            if ("DATASET".equals(type) || "VIEW".equals(type)) {
                String view = firstText(row.getAllowedView(), row.getTableName(), row.getSemanticKey());
                if (!StringUtils.hasText(view)) {
                    continue;
                }
                Nl2SqlProperties.Table table = tables.computeIfAbsent(view, key -> table(key, row));
                mergeAliases(table, row);
            } else if ("FIELD".equals(type) || "COLUMN".equals(type) || "METRIC".equals(type) || "DIMENSION".equals(type)) {
                String view = firstText(row.getAllowedView(), row.getTableName(), row.getDatasetName());
                String columnName = firstText(row.getColumnName(), row.getSemanticKey());
                if (!StringUtils.hasText(view) || !StringUtils.hasText(columnName)) {
                    continue;
                }
                Nl2SqlProperties.Table table = tables.computeIfAbsent(view, key -> table(key, row));
                table.getColumns().add(column(columnName, row));
                mergeAliases(table, row);
            } else if ("TERM".equals(type)) {
                Nl2SqlProperties.Term term = new Nl2SqlProperties.Term();
                term.setName(firstText(row.getDisplayName(), row.getSemanticKey()));
                term.setDescription(firstText(row.getExpressionSql(), description(row), row.getSemanticKey()));
                terms.add(term);
            } else if ("EXAMPLE".equals(type) && StringUtils.hasText(row.getExampleSql())) {
                Nl2SqlProperties.Example example = new Nl2SqlProperties.Example();
                example.setQuestion(firstText(row.getDisplayName(), row.getSemanticKey()));
                example.setSql(row.getExampleSql());
                examples.add(example);
            }
        }
        List<Nl2SqlProperties.Table> sortedTables = tables.values().stream()
                .sorted(Comparator.comparing(Nl2SqlProperties.Table::getName))
                .toList();
        return new CatalogSnapshot(DEFAULT_DATASOURCE, Math.max(version, 1), sortedTables, terms, examples);
    }

    private Nl2SqlProperties.Table table(String view, AiNl2SqlSemanticCatalog row) {
        Nl2SqlProperties.Table table = new Nl2SqlProperties.Table();
        table.setName(view);
        table.setDescription(description(row));
        table.setAllowed(true);
        return table;
    }

    private Nl2SqlProperties.Column column(String columnName, AiNl2SqlSemanticCatalog row) {
        Nl2SqlProperties.Column column = new Nl2SqlProperties.Column();
        column.setName(columnName);
        column.setType(firstText(ext(row).getString("dataType"), ext(row).getString("type"), "VARCHAR"));
        column.setDescription(description(row));
        column.setSensitive(!"PUBLIC".equalsIgnoreCase(firstText(row.getSensitivityLevel(), "PUBLIC")));
        return column;
    }

    private void mergeAliases(Nl2SqlProperties.Table table, AiNl2SqlSemanticCatalog row) {
        JSONObject ext = ext(row);
        JSONArray aliases = ext.getJSONArray("aliases");
        List<String> aliasList = aliases == null ? List.of() : aliases.toJavaList(String.class);
        for (String alias : aliasList) {
            if (StringUtils.hasText(alias) && !table.getAliases().contains(alias)) {
                table.getAliases().add(alias);
            }
        }
        if (StringUtils.hasText(row.getDisplayName()) && !table.getAliases().contains(row.getDisplayName())) {
            table.getAliases().add(row.getDisplayName());
        }
    }

    private String description(AiNl2SqlSemanticCatalog row) {
        return firstText(ext(row).getString("description"), row.getDisplayName(), row.getSemanticKey());
    }

    private JSONObject ext(AiNl2SqlSemanticCatalog row) {
        if (!StringUtils.hasText(row.getExtJson())) {
            return new JSONObject();
        }
        try {
            return JSON.parseObject(row.getExtJson());
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return "";
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    public record CatalogSnapshot(String datasourceKey,
                                  int schemaVersion,
                                  List<Nl2SqlProperties.Table> tables,
                                  List<Nl2SqlProperties.Term> terms,
                                  List<Nl2SqlProperties.Example> examples) {
    }
}
