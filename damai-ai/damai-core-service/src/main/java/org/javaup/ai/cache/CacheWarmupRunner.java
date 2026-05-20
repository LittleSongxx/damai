package org.javaup.ai.cache;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.entity.FaqEntry;
import org.javaup.ai.mapper.FaqEntryMapper;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Slf4j
@Component
public class CacheWarmupRunner implements CommandLineRunner {

    private final FaqEntryMapper faqEntryMapper;
    private final OpenAiEmbeddingModel embeddingModel;
    private final CacheManager cacheManager;

    public CacheWarmupRunner(FaqEntryMapper faqEntryMapper,
                             OpenAiEmbeddingModel embeddingModel,
                             CacheManager cacheManager) {
        this.faqEntryMapper = faqEntryMapper;
        this.embeddingModel = embeddingModel;
        this.cacheManager = cacheManager;
    }

    @Override
    public void run(String... args) {
        Thread warmupThread = new Thread(this::warmupEmbeddingCache, "cache-warmup");
        warmupThread.setDaemon(true);
        warmupThread.start();
    }

    private void warmupEmbeddingCache() {
        try {
            List<FaqEntry> highPriorityFaqs = faqEntryMapper.selectList(
                    Wrappers.lambdaQuery(FaqEntry.class)
                            .eq(FaqEntry::getEnabled, 1)
                            .gt(FaqEntry::getPriority, 0)
                            .orderByDesc(FaqEntry::getPriority)
                            .last("limit 200"));
            if (highPriorityFaqs.isEmpty()) {
                log.info("Cache warmup: no high-priority FAQ entries found, skipping");
                return;
            }
            log.info("Cache warmup: preloading embeddings for {} high-priority FAQs", highPriorityFaqs.size());
            int loaded = 0;
            for (FaqEntry faq : highPriorityFaqs) {
                try {
                    String text = faq.getQuestion();
                    if (!StringUtils.hasText(text)) continue;
                    if (cacheManager.getEmbedding(text) != null) continue;
                    float[] vector = embeddingModel.embed(text);
                    if (vector != null && vector.length > 0) {
                        cacheManager.putEmbedding(text, vector);
                        loaded++;
                    }
                    var similarQuestions = faq.getSimilarQuestionsJson();
                    if (StringUtils.hasText(similarQuestions)) {
                        try {
                            List<String> questions = JSON.parseArray(similarQuestions, String.class);
                            if (questions != null) {
                                for (String q : questions) {
                                    if (StringUtils.hasText(q) && cacheManager.getEmbedding(q) == null) {
                                        float[] qVector = embeddingModel.embed(q);
                                        if (qVector != null && qVector.length > 0) {
                                            cacheManager.putEmbedding(q, qVector);
                                            loaded++;
                                        }
                                    }
                                }
                            }
                        } catch (Exception ignored) {
                            // Skip malformed similarQuestions
                        }
                    }
                } catch (Exception e) {
                    log.warn("Cache warmup: failed to compute embedding for faqId={}: {}",
                            faq.getFaqId(), e.getMessage());
                }
            }
            log.info("Cache warmup complete: {} embeddings loaded", loaded);
        } catch (Exception e) {
            log.warn("Cache warmup failed: {}", e.getMessage());
        }
    }
}
