package org.javaup.ai.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class CacheMetrics {

    private final Counter embeddingHit;
    private final Counter embeddingMiss;
    private final Counter faqSearchHit;
    private final Counter faqSearchMiss;
    private final Counter webSearchHit;
    private final Counter webSearchMiss;
    private final Counter userContextHit;
    private final Counter userContextMiss;
    private final Counter nl2sqlSchemaHit;
    private final Counter nl2sqlSchemaMiss;

    public CacheMetrics(MeterRegistry registry) {
        this.embeddingHit = Counter.builder("damai.cache.embedding.hit")
                .description("Embedding cache hits").register(registry);
        this.embeddingMiss = Counter.builder("damai.cache.embedding.miss")
                .description("Embedding cache misses").register(registry);
        this.faqSearchHit = Counter.builder("damai.cache.faq_search.hit")
                .description("FAQ search cache hits").register(registry);
        this.faqSearchMiss = Counter.builder("damai.cache.faq_search.miss")
                .description("FAQ search cache misses").register(registry);
        this.webSearchHit = Counter.builder("damai.cache.web_search.hit")
                .description("Web search cache hits").register(registry);
        this.webSearchMiss = Counter.builder("damai.cache.web_search.miss")
                .description("Web search cache misses").register(registry);
        this.userContextHit = Counter.builder("damai.cache.user_context.hit")
                .description("User context cache hits").register(registry);
        this.userContextMiss = Counter.builder("damai.cache.user_context.miss")
                .description("User context cache misses").register(registry);
        this.nl2sqlSchemaHit = Counter.builder("damai.cache.nl2sql_schema.hit")
                .description("NL2SQL schema cache hits").register(registry);
        this.nl2sqlSchemaMiss = Counter.builder("damai.cache.nl2sql_schema.miss")
                .description("NL2SQL schema cache misses").register(registry);
    }

    public void recordEmbedding(boolean hit) { if (hit) embeddingHit.increment(); else embeddingMiss.increment(); }
    public void recordFaqSearch(boolean hit) { if (hit) faqSearchHit.increment(); else faqSearchMiss.increment(); }
    public void recordWebSearch(boolean hit) { if (hit) webSearchHit.increment(); else webSearchMiss.increment(); }
    public void recordUserContext(boolean hit) { if (hit) userContextHit.increment(); else userContextMiss.increment(); }
    public void recordNl2sqlSchema(boolean hit) { if (hit) nl2sqlSchemaHit.increment(); else nl2sqlSchemaMiss.increment(); }
}
