package org.javaup.ai.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.entity.AiRun;
import org.javaup.ai.entity.FaqEntry;
import org.javaup.ai.mapper.AiRunMapper;
import org.javaup.ai.mapper.FaqEntryMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class FaqAutoMiningService {

    private final AiRunMapper runMapper;
    private final FaqEntryMapper faqEntryMapper;
    private final ChatClient chatClient;
    private final FaqMatchService faqMatchService;

    public FaqAutoMiningService(AiRunMapper runMapper,
                                 FaqEntryMapper faqEntryMapper,
                                 @Qualifier("unifiedChatClient") ChatClient chatClient,
                                 FaqMatchService faqMatchService) {
        this.runMapper = runMapper;
        this.faqEntryMapper = faqEntryMapper;
        this.chatClient = chatClient;
        this.faqMatchService = faqMatchService;
    }

    @Scheduled(cron = "${damai.ai.faq.mining.cron:0 0 4 * * ?}")
    @Transactional
    public void mineFaqCandidates() {
        log.info("Starting FAQ auto-mining...");
        try {
            List<AiRun> successfulRuns = fetchSuccessfulRuns();
            if (successfulRuns.size() < 10) {
                log.info("Not enough successful runs for FAQ mining (got {})", successfulRuns.size());
                return;
            }
            List<QuestionCluster> clusters = clusterQuestions(successfulRuns);
            for (QuestionCluster cluster : clusters) {
                if (cluster.count() < 3) continue;
                if (faqAlreadyExists(cluster.representativeQuestion())) continue;

                FaqDraft draft = generateFaqDraft(cluster);
                if (draft != null) {
                    saveFaqDraft(draft);
                    log.info("FAQ draft created: question='{}', clusterSize={}",
                            draft.question(), cluster.count());
                }
            }
        } catch (Exception e) {
            log.warn("FAQ auto-mining failed: {}", e.getMessage());
        }
    }

    private List<AiRun> fetchSuccessfulRuns() {
        Date sevenDaysAgo = new Date(System.currentTimeMillis() - 7L * 24 * 3600 * 1000);
        return runMapper.selectList(
                Wrappers.lambdaQuery(AiRun.class)
                        .eq(AiRun::getStatus, 1)
                        .eq(AiRun::getRunStatus, "COMPLETED")
                        .isNotNull(AiRun::getResponseSummary)
                        .ne(AiRun::getResponseSummary, "")
                        .ge(AiRun::getCreateTime, sevenDaysAgo)
                        .orderByDesc(AiRun::getCreateTime)
                        .last("limit 500"));
    }

    private List<QuestionCluster> clusterQuestions(List<AiRun> runs) {
        List<AiRun> withQuestions = runs.stream()
                .filter(r -> StringUtils.hasText(r.getUserMessage()))
                .filter(r -> r.getUserMessage().length() >= 4)
                .filter(r -> isNotRefused(r))
                .limit(200)
                .toList();

        java.util.Map<String, QuestionCluster> clusters = new java.util.LinkedHashMap<>();
        for (AiRun run : withQuestions) {
            String question = run.getUserMessage().trim();
            String shortForm = shorten(question, 30);
            clusters.compute(shortForm, (k, v) -> v == null
                    ? new QuestionCluster(question, run.getResponseSummary(), 1)
                    : v.increment());
        }
        return clusters.values().stream()
                .filter(c -> c.count() >= 3)
                .sorted(Comparator.comparingInt(QuestionCluster::count).reversed())
                .limit(20)
                .toList();
    }

    private boolean isNotRefused(AiRun run) {
        String response = run.getResponseSummary();
        if (response == null) return true;
        return !response.contains("证据不够扎实") && !response.contains("不能直接给出确定结论");
    }

    private boolean faqAlreadyExists(String question) {
        FaqMatchService.FaqMatchResult match = faqMatchService.match(question);
        return match != null;
    }

    private FaqDraft generateFaqDraft(QuestionCluster cluster) {
        try {
            String prompt = String.format("""
                    你是FAQ撰写助手。根据用户高频问题和助手回答，生成一条FAQ条目。

                    用户高频问题（出现%d次）：%s
                    助手典型回答：%s

                    生成JSON格式（只输出JSON，不要Markdown包裹）：
                    {
                      "question": "简洁的用户问法",
                      "answer": "准确完整的回答",
                      "keywords": "关键词1,关键词2,关键词3",
                      "category": "适合的分类"
                    }
                    """, cluster.count(), cluster.representativeQuestion(), cluster.typicalAnswer());

            String raw = chatClient.prompt().user(prompt).call().content();
            if (raw == null || raw.isBlank()) return null;

            String jsonStr = raw.trim().replaceAll("```\\w*\\n?", "").replaceAll("```", "").trim();
            var json = com.alibaba.fastjson2.JSON.parseObject(jsonStr);
            return new FaqDraft(
                    json.getString("question"),
                    json.getString("answer"),
                    json.getString("keywords"),
                    json.getString("category"));
        } catch (Exception e) {
            log.warn("FAQ draft generation failed: {}", e.getMessage());
            return null;
        }
    }

    private void saveFaqDraft(FaqDraft draft) {
        FaqEntry entry = new FaqEntry();
        entry.setFaqId(UUID.randomUUID().toString().replace("-", ""));
        entry.setQuestion(draft.question());
        entry.setAnswer(draft.answer());
        entry.setKeywords(draft.keywords());
        entry.setCategory(draft.category());
        entry.setPriority(0);
        entry.setHitCount(0);
        entry.setEnabled(0);
        entry.setEmbeddingCached(0);
        entry.setCreateTime(new Date());
        entry.setEditTime(new Date());
        entry.setStatus(1);
        faqEntryMapper.insert(entry);
    }

    private String shorten(String text, int maxLen) {
        if (text == null) return "";
        String cleaned = text.replaceAll("[？?！!。，,、\\s]+", "");
        return cleaned.length() <= maxLen ? cleaned : cleaned.substring(0, maxLen);
    }

    private record QuestionCluster(String representativeQuestion, String typicalAnswer, int count) {
        QuestionCluster increment() {
            return new QuestionCluster(representativeQuestion, typicalAnswer, count + 1);
        }
    }

    private record FaqDraft(String question, String answer, String keywords, String category) {}
}
