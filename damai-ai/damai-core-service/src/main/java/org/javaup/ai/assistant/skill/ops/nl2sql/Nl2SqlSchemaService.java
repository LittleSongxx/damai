package org.javaup.ai.assistant.skill.ops.nl2sql;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class Nl2SqlSchemaService {

    private final Nl2SqlProperties properties;
    private final CacheManager cacheManager;

    public Nl2SqlSchemaContext retrieve(String question) {
        String dsKey = properties.getDatasource().getUrl() != null
                ? properties.getDatasource().getUrl() : "default";
        Nl2SqlSchemaContext cached = cacheManager.getNl2sqlSchema(dsKey);
        if (cached != null) {
            return cached;
        }
        Nl2SqlSchemaContext context = doRetrieve(question);
        cacheManager.putNl2sqlSchema(dsKey, context);
        return context;
    }

    private Nl2SqlSchemaContext doRetrieve(String question) {
        List<Nl2SqlProperties.Table> candidates = properties.getTables().stream()
                .filter(Nl2SqlProperties.Table::isAllowed)
                .map(table -> new ScoredTable(table, score(table, question)))
                .sorted(Comparator.comparingInt(ScoredTable::score).reversed())
                .filter(scored -> scored.score() > 0)
                .limit(Math.max(1, properties.getSchemaTopK()))
                .map(ScoredTable::table)
                .collect(Collectors.toList());
        if (candidates.isEmpty()) {
            candidates = properties.getTables().stream()
                    .filter(Nl2SqlProperties.Table::isAllowed)
                    .limit(Math.max(1, properties.getSchemaTopK()))
                    .collect(Collectors.toList());
        }
        List<Nl2SqlProperties.Example> examples = selectExamples(question);
        return new Nl2SqlSchemaContext(candidates, properties.getTerms(), examples, formatSchema(candidates));
    }

    public List<String> allowedTableNames() {
        return properties.getTables().stream()
                .filter(Nl2SqlProperties.Table::isAllowed)
                .map(Nl2SqlProperties.Table::getName)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .toList();
    }

    private List<Nl2SqlProperties.Example> selectExamples(String question) {
        String normalized = normalize(question);
        return properties.getExamples().stream()
                .map(example -> new ScoredExample(example, overlapScore(normalized, normalize(example.getQuestion()))))
                .sorted(Comparator.comparingInt(ScoredExample::score).reversed())
                .limit(3)
                .map(ScoredExample::example)
                .toList();
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

    private record ScoredTable(Nl2SqlProperties.Table table, int score) {
    }

    private record ScoredExample(Nl2SqlProperties.Example example, int score) {
    }
}
