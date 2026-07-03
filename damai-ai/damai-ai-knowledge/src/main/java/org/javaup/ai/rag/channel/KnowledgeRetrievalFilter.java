package org.javaup.ai.rag.channel;

import lombok.Builder;
import lombok.Data;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

/**
 * Query-time knowledge filter.  It is intentionally explicit instead of a
 * generic metadata map so both vector and keyword retrieval must honor the same
 * customer-service scope.
 */
@Data
@Builder
public class KnowledgeRetrievalFilter {

    private String scope;
    private String topic;
    private List<String> documentIds;
    private String audience;
    private String region;
    private String channel;
    private Long validAt;
    private String userScope;

    public static KnowledgeRetrievalFilter empty() {
        return KnowledgeRetrievalFilter.builder().build();
    }

    public boolean hasAnyConstraint() {
        return StringUtils.hasText(scope)
                || StringUtils.hasText(topic)
                || (documentIds != null && documentIds.stream().anyMatch(StringUtils::hasText))
                || StringUtils.hasText(audience)
                || StringUtils.hasText(region)
                || StringUtils.hasText(channel)
                || StringUtils.hasText(userScope)
                || validAt != null;
    }

    public List<String> normalizedDocumentIds() {
        if (documentIds == null) {
            return List.of();
        }
        return documentIds.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
    }

    public KnowledgeRetrievalFilter merge(KnowledgeRetrievalFilter fallback) {
        if (fallback == null) {
            return this;
        }
        return KnowledgeRetrievalFilter.builder()
                .scope(firstText(scope, fallback.scope))
                .topic(firstText(topic, fallback.topic))
                .documentIds(!normalizedDocumentIds().isEmpty() ? normalizedDocumentIds() : fallback.normalizedDocumentIds())
                .audience(firstText(audience, fallback.audience))
                .region(firstText(region, fallback.region))
                .channel(firstText(channel, fallback.channel))
                .validAt(validAt != null ? validAt : fallback.validAt)
                .userScope(firstText(userScope, fallback.userScope))
                .build();
    }

    private String firstText(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary : (StringUtils.hasText(fallback) ? fallback : null);
    }

    public static boolean same(KnowledgeRetrievalFilter a, KnowledgeRetrievalFilter b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return Objects.equals(a.scope, b.scope)
                && Objects.equals(a.topic, b.topic)
                && Objects.equals(a.normalizedDocumentIds(), b.normalizedDocumentIds())
                && Objects.equals(a.audience, b.audience)
                && Objects.equals(a.region, b.region)
                && Objects.equals(a.channel, b.channel)
                && Objects.equals(a.validAt, b.validAt)
                && Objects.equals(a.userScope, b.userScope);
    }
}
