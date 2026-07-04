package org.javaup.ai.assistant.skill.ops.nl2sql;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.cache.Nl2SqlCacheService;
import org.javaup.ai.service.Nl2SqlSemanticCatalogService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class Nl2SqlSchemaService {

    private final Nl2SqlProperties properties;
    private final Nl2SqlCacheService nl2SqlCacheService;
    private final Nl2SqlSemanticCatalogService semanticCatalogService;

    public Nl2SqlSchemaContext retrieve(String question) {
        return retrieve(question, "global");
    }

    public Nl2SqlSchemaContext retrieve(String question, String userScope) {
        String cacheKey = schemaLinkingCacheKey(question, userScope);
        Nl2SqlSchemaContext cached = nl2SqlCacheService.getSchema(cacheKey);
        if (cached != null) {
            return cached;
        }
        Nl2SqlSchemaContext context = doRetrieve(question, semanticCatalogService.activeSnapshot());
        nl2SqlCacheService.putSchema(cacheKey, context);
        return context;
    }

    private Nl2SqlSchemaContext doRetrieve(String question, Nl2SqlSemanticCatalogService.CatalogSnapshot catalog) {
        List<Nl2SqlProperties.Table> candidates = catalog.tables().stream()
                .filter(Nl2SqlProperties.Table::isAllowed)
                .map(table -> new ScoredTable(table, score(table, question)))
                .sorted(Comparator.comparingInt(ScoredTable::score).reversed())
                .filter(scored -> scored.score() > 0)
                .limit(Math.max(1, properties.getSchemaTopK()))
                .map(ScoredTable::table)
                .collect(Collectors.toList());
        if (candidates.isEmpty()) {
            candidates = catalog.tables().stream()
                    .filter(Nl2SqlProperties.Table::isAllowed)
                    .limit(Math.max(1, properties.getSchemaTopK()))
                    .collect(Collectors.toList());
        }
        List<Nl2SqlProperties.Example> examples = selectExamples(question, catalog.examples());
        return new Nl2SqlSchemaContext(candidates, catalog.terms(), examples, formatSchema(candidates));
    }

    public List<String> allowedTableNames() {
        return semanticCatalogService.activeSnapshot().tables().stream()
                .filter(Nl2SqlProperties.Table::isAllowed)
                .map(Nl2SqlProperties.Table::getName)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .toList();
    }

    public Map<String, Set<String>> allowedColumnsByTable() {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (Nl2SqlProperties.Table table : semanticCatalogService.activeSnapshot().tables()) {
            if (!table.isAllowed() || !StringUtils.hasText(table.getName())) {
                continue;
            }
            Set<String> columns = new LinkedHashSet<>();
            if (table.getColumns() != null) {
                for (Nl2SqlProperties.Column column : table.getColumns()) {
                    if (!StringUtils.hasText(column.getName()) || column.isSensitive()) {
                        continue;
                    }
                    columns.add(column.getName().toLowerCase(Locale.ROOT));
                }
            }
            result.put(table.getName().toLowerCase(Locale.ROOT), columns);
        }
        return result;
    }

    private List<Nl2SqlProperties.Example> selectExamples(String question, List<Nl2SqlProperties.Example> examples) {
        String normalized = normalize(question);
        return examples.stream()
                .map(example -> new ScoredExample(example, overlapScore(normalized, normalize(example.getQuestion()))))
                .sorted(Comparator.comparingInt(ScoredExample::score).reversed())
                .limit(3)
                .map(ScoredExample::example)
                .toList();
    }

    private String schemaLinkingCacheKey(String question, String userScope) {
        Nl2SqlSemanticCatalogService.CatalogSnapshot catalog = semanticCatalogService.activeSnapshot();
        return "nl2sql:schema-linking:"
                + datasourceFingerprint(catalog.datasourceKey())
                + ":schemaVersion:" + catalogFingerprint(catalog)
                + ":topK:" + Math.max(1, properties.getSchemaTopK())
                + ":question:" + sha256(normalize(question))
                + ":scope:" + sha256(StringUtils.hasText(userScope) ? userScope : "global");
    }

    private String datasourceFingerprint(String datasourceKey) {
        String url = StringUtils.hasText(datasourceKey)
                ? datasourceKey
                : "default";
        return sha256(url);
    }

    private String catalogFingerprint(Nl2SqlSemanticCatalogService.CatalogSnapshot catalog) {
        StringBuilder builder = new StringBuilder();
        builder.append("version:").append(catalog.schemaVersion()).append(';');
        for (Nl2SqlProperties.Table table : catalog.tables()) {
            builder.append(table.getName()).append('|')
                    .append(table.isAllowed()).append('|')
                    .append(table.getDescription()).append('|');
            if (table.getColumns() != null) {
                for (Nl2SqlProperties.Column column : table.getColumns()) {
                    builder.append(column.getName()).append(':')
                            .append(column.getType()).append(':')
                            .append(column.getDescription()).append(':')
                            .append(column.isSensitive()).append(',');
                }
            }
            builder.append(';');
        }
        for (Nl2SqlProperties.Term term : catalog.terms()) {
            builder.append("term:").append(term.getName()).append(':').append(term.getDescription()).append(';');
        }
        for (Nl2SqlProperties.Example example : catalog.examples()) {
            builder.append("example:").append(example.getQuestion()).append(':').append(example.getSql()).append(';');
        }
        return sha256(builder.toString());
    }

    private int score(Nl2SqlProperties.Table table, String question) {
        String normalized = normalize(question);
        StringBuilder text = new StringBuilder();
        append(text, table.getName());
        append(text, table.getDescription());
        if (table.getAliases() != null) {
            table.getAliases().forEach(alias -> append(text, alias));
        }
        if (table.getColumns() != null) {
            table.getColumns().forEach(column -> {
                append(text, column.getName());
                append(text, column.getDescription());
            });
        }
        return overlapScore(normalized, normalize(text.toString()));
    }

    private int overlapScore(String question, String target) {
        if (!StringUtils.hasText(question) || !StringUtils.hasText(target)) {
            return 0;
        }
        int score = 0;
        for (String token : target.split("[,，。\\s_\\-]+")) {
            if (token.length() >= 2 && question.contains(token)) {
                score += token.length();
            }
        }
        for (String token : question.split("[,，。\\s_\\-]+")) {
            if (token.length() >= 2 && target.contains(token)) {
                score += token.length();
            }
        }
        return score;
    }

    private String formatSchema(List<Nl2SqlProperties.Table> tables) {
        StringBuilder builder = new StringBuilder();
        for (Nl2SqlProperties.Table table : tables) {
            builder.append("TABLE ").append(table.getName()).append(": ")
                    .append(nullToEmpty(table.getDescription())).append('\n');
            if (table.getColumns() != null) {
                for (Nl2SqlProperties.Column column : table.getColumns()) {
                    if (column.isSensitive()) {
                        continue;
                    }
                    builder.append("- ")
                            .append(column.getName())
                            .append(" ")
                            .append(nullToEmpty(column.getType()))
                            .append(": ")
                            .append(nullToEmpty(column.getDescription()))
                            .append('\n');
                }
            }
        }
        return builder.toString();
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private void append(StringBuilder builder, String value) {
        if (StringUtils.hasText(value)) {
            builder.append(value).append(' ');
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm is not available", ex);
        }
    }

    private record ScoredTable(Nl2SqlProperties.Table table, int score) {
    }

    private record ScoredExample(Nl2SqlProperties.Example example, int score) {
    }
}
