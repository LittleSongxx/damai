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
    private final Counter nl2sqlResultHit;
    private final Counter nl2sqlResultMiss;
    private final Counter ragEvidenceExactHit;
    private final Counter ragEvidenceExactMiss;
    private final Counter ragEvidenceSemanticHit;
    private final Counter ragEvidenceSemanticMiss;
    private final Counter ragEvidencePut;
    private final Counter ragEvidenceInvalidate;

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
        this.nl2sqlResultHit = Counter.builder("damai.cache.nl2sql_result.hit")
                .description("NL2SQL result cache hits").register(registry);
        this.nl2sqlResultMiss = Counter.builder("damai.cache.nl2sql_result.miss")
                .description("NL2SQL result cache misses").register(registry);
        this.ragEvidenceExactHit = Counter.builder("damai.cache.rag_evidence.exact.hit")
                .description("RAG evidence exact cache hits").register(registry);
        this.ragEvidenceExactMiss = Counter.builder("damai.cache.rag_evidence.exact.miss")
                .description("RAG evidence exact cache misses").register(registry);
        this.ragEvidenceSemanticHit = Counter.builder("damai.cache.rag_evidence.semantic.hit")
                .description("RAG evidence semantic cache hits").register(registry);
        this.ragEvidenceSemanticMiss = Counter.builder("damai.cache.rag_evidence.semantic.miss")
                .description("RAG evidence semantic cache misses").register(registry);
        this.ragEvidencePut = Counter.builder("damai.cache.rag_evidence.put")
                .description("RAG evidence cache writes").register(registry);
        this.ragEvidenceInvalidate = Counter.builder("damai.cache.rag_evidence.invalidate")
                .description("RAG evidence cache invalidations").register(registry);
    }

    public void recordEmbedding(boolean hit) { if (hit) embeddingHit.increment(); else embeddingMiss.increment(); }
    public void recordFaqSearch(boolean hit) { if (hit) faqSearchHit.increment(); else faqSearchMiss.increment(); }
    public void recordWebSearch(boolean hit) { if (hit) webSearchHit.increment(); else webSearchMiss.increment(); }
    public void recordUserContext(boolean hit) { if (hit) userContextHit.increment(); else userContextMiss.increment(); }
    public void recordNl2sqlSchema(boolean hit) { if (hit) nl2sqlSchemaHit.increment(); else nl2sqlSchemaMiss.increment(); }
    public void recordNl2sqlResult(boolean hit) { if (hit) nl2sqlResultHit.increment(); else nl2sqlResultMiss.increment(); }
    public void recordRagEvidenceExact(boolean hit) { if (hit) ragEvidenceExactHit.increment(); else ragEvidenceExactMiss.increment(); }
    public void recordRagEvidenceSemantic(boolean hit) { if (hit) ragEvidenceSemanticHit.increment(); else ragEvidenceSemanticMiss.increment(); }
    public void recordRagEvidencePut() { ragEvidencePut.increment(); }
    public void recordRagEvidenceInvalidate() { ragEvidenceInvalidate.increment(); }
}
