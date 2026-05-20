package org.javaup.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.entity.AiRagEvalCase;
import org.javaup.ai.entity.AiRagEvalResult;
import org.javaup.ai.entity.AiRagEvalRun;
import org.javaup.ai.mapper.AiRagEvalCaseMapper;
import org.javaup.ai.mapper.AiRagEvalResultMapper;
import org.javaup.ai.mapper.AiRagEvalRunMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class RagEvalService {

    private final AiRagEvalCaseMapper caseMapper;
    private final AiRagEvalRunMapper runMapper;
    private final AiRagEvalResultMapper resultMapper;
    private final HybridSearchService hybridSearchService;
    private final ChatClient judgeClient;

    public RagEvalService(AiRagEvalCaseMapper caseMapper,
                           AiRagEvalRunMapper runMapper,
                           AiRagEvalResultMapper resultMapper,
                           HybridSearchService hybridSearchService,
                           @Qualifier("unifiedChatClient") ChatClient judgeClient) {
        this.caseMapper = caseMapper;
        this.runMapper = runMapper;
        this.resultMapper = resultMapper;
        this.hybridSearchService = hybridSearchService;
        this.judgeClient = judgeClient;
    }

    public AiRagEvalRun startEvaluation() {
        List<AiRagEvalCase> cases = caseMapper.selectList(
                new LambdaQueryWrapper<AiRagEvalCase>()
                        .eq(AiRagEvalCase::getStatus, 1));
        if (cases.isEmpty()) {
            throw new RuntimeException("No eval cases found");
        }

        AiRagEvalRun evalRun = new AiRagEvalRun();
        evalRun.setEvalRunId(UUID.randomUUID().toString().replace("-", ""));
        evalRun.setTotalCases(cases.size());
        evalRun.setCompletedCases(0);
        evalRun.setRunStatus("RUNNING");
        evalRun.setCreateTime(new Date());
        evalRun.setEditTime(new Date());
        evalRun.setStatus(1);
        runMapper.insert(evalRun);

        executeEvalAsync(evalRun, cases);
        return evalRun;
    }

    @Async("aiTraceExecutor")
    public void executeEvalAsync(AiRagEvalRun evalRun, List<AiRagEvalCase> cases) {
        int completed = 0;
        double totalRecall = 0, totalMrr = 0, totalNdcg = 0;
        double totalFaithfulness = 0, totalAnswerRelevancy = 0, totalCompleteness = 0;
        int answerEvalCount = 0;

        for (AiRagEvalCase evalCase : cases) {
            try {
                long start = System.currentTimeMillis();
                List<String> retrievedChunks = hybridSearchService.searchChunkIds(evalCase.getQuestion(), 5);
                long latency = System.currentTimeMillis() - start;

                List<String> expectedChunks = parseChunkIds(evalCase.getExpectedChunks());
                double recall = calculateRecallAtK(retrievedChunks, expectedChunks, 5);
                double mrr = calculateMrr(retrievedChunks, expectedChunks);
                double ndcg = calculateNdcgAtK(retrievedChunks, expectedChunks, 5);

                totalRecall += recall;
                totalMrr += mrr;
                totalNdcg += ndcg;

                AiRagEvalResult result = new AiRagEvalResult();
                result.setEvalRunId(evalRun.getEvalRunId());
                result.setCaseId(evalCase.getCaseId());
                result.setQuestion(evalCase.getQuestion());
                result.setRetrievedChunks(String.join(",", retrievedChunks));
                result.setRecallAt5(recall);
                result.setMrr(mrr);
                result.setNdcgAt5(ndcg);
                result.setLatencyMs(latency);

                if (StringUtils.hasText(evalCase.getExpectedAnswer())) {
                    AnswerQuality quality = judgeAnswerQuality(
                            evalCase.getQuestion(), retrievedChunks, evalCase.getExpectedAnswer());
                    if (quality != null) {
                        result.setGeneratedAnswer(quality.generatedAnswer());
                        result.setFaithfulnessScore(quality.faithfulness());
                        totalFaithfulness += quality.faithfulness();
                        totalAnswerRelevancy += quality.answerRelevancy();
                        totalCompleteness += quality.completeness();
                        answerEvalCount++;
                    }
                }

                result.setCreateTime(new Date());
                result.setEditTime(new Date());
                result.setStatus(1);
                resultMapper.insert(result);
                completed++;
            } catch (Exception e) {
                log.warn("Eval case failed: caseId={}, error={}", evalCase.getCaseId(), e.getMessage());
            }
        }

        evalRun.setCompletedCases(completed);
        evalRun.setAvgRecall(completed > 0 ? totalRecall / completed : 0);
        evalRun.setAvgMrr(completed > 0 ? totalMrr / completed : 0);
        evalRun.setAvgNdcg(completed > 0 ? totalNdcg / completed : 0);
        if (answerEvalCount > 0) {
            evalRun.setAvgFaithfulness(totalFaithfulness / answerEvalCount);
        }
        evalRun.setRunStatus("COMPLETED");
        evalRun.setEditTime(new Date());
        runMapper.updateById(evalRun);
        log.info("RAG eval completed: evalRunId={}, cases={}, avgRecall={}, avgMRR={}, avgNDCG={}, answerEvalCount={}",
                evalRun.getEvalRunId(), completed, evalRun.getAvgRecall(), evalRun.getAvgMrr(), evalRun.getAvgNdcg(), answerEvalCount);
    }

    private AnswerQuality judgeAnswerQuality(String question, List<String> retrievedChunks, String expectedAnswer) {
        try {
            String chunksBlock = String.join("\n---\n", retrievedChunks);
            String prompt = String.format("""
                    你是一个RAG系统评估Judge。根据检索到的文档和参考答案，评估以下问题的生成答案质量。
                    请对以下三个维度打分(0.0-1.0)，并输出JSON。

                    【问题】%s

                    【检索到的文档】%s

                    【参考答案】%s

                    请输出JSON格式（不要Markdown包裹）：
                    {
                      "faithfulness": 0.0-1.0,
                      "answerRelevancy": 0.0-1.0,
                      "completeness": 0.0-1.0,
                      "generatedAnswer": "基于检索文档生成的答案"
                    }

                    faithfulness: 生成的答案是否忠实于检索文档，没有编造
                    answerRelevancy: 答案是否回应了问题
                    completeness: 答案是否覆盖了参考答案的关键点
                    """, question, chunksBlock, expectedAnswer);

            String raw = judgeClient.prompt().user(prompt).call().content();
            if (raw == null || raw.isBlank()) return null;

            String jsonStr = raw.trim().replaceAll("```\\w*\\n?", "").replaceAll("```", "").trim();
            JSONObject json = JSON.parseObject(jsonStr);
            return new AnswerQuality(
                    json.getDoubleValue("faithfulness"),
                    json.getDoubleValue("answerRelevancy"),
                    json.getDoubleValue("completeness"),
                    json.getString("generatedAnswer"));
        } catch (Exception e) {
            log.warn("LLM-as-Judge evaluation failed: {}", e.getMessage());
            return null;
        }
    }

    private record AnswerQuality(double faithfulness, double answerRelevancy, double completeness, String generatedAnswer) {}

    public AiRagEvalRun getRunStatus(String evalRunId) {
        return runMapper.selectOne(
                new LambdaQueryWrapper<AiRagEvalRun>()
                        .eq(AiRagEvalRun::getEvalRunId, evalRunId));
    }

    private List<String> parseChunkIds(String json) {
        if (json == null || json.isEmpty()) return List.of();
        return List.of(json.replace("[", "").replace("]", "").replace("\"", "").split(","));
    }

    private double calculateRecallAtK(List<String> retrieved, List<String> expected, int k) {
        if (expected.isEmpty()) return 1.0;
        long hits = retrieved.stream().limit(k).filter(expected::contains).count();
        return (double) hits / expected.size();
    }

    private double calculateMrr(List<String> retrieved, List<String> expected) {
        for (int i = 0; i < retrieved.size(); i++) {
            if (expected.contains(retrieved.get(i))) {
                return 1.0 / (i + 1);
            }
        }
        return 0.0;
    }

    private double calculateNdcgAtK(List<String> retrieved, List<String> expected, int k) {
        double dcg = 0;
        for (int i = 0; i < Math.min(retrieved.size(), k); i++) {
            if (expected.contains(retrieved.get(i))) {
                dcg += 1.0 / (Math.log(i + 2) / Math.log(2));
            }
        }
        double idealDcg = 0;
        for (int i = 0; i < Math.min(expected.size(), k); i++) {
            idealDcg += 1.0 / (Math.log(i + 2) / Math.log(2));
        }
        return idealDcg > 0 ? dcg / idealDcg : 0;
    }
}
