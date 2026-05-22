package org.javaup.ai.assistant.eval;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RAG 评估结果 —— 遵循 RAGAS 评估体系。
 *
 * <p>包含三类指标:
 * <ul>
 *   <li><b>传统检索指标</b>: recallAtK, mrr, ndcgAtK（基于 ground-truth chunk ID 匹配）</li>
 *   <li><b>RAGAS 检索质量</b>: contextPrecision, contextRecall, contextRelevance（LLM-as-Judge）</li>
 *   <li><b>RAGAS 生成质量</b>: faithfulness, answerRelevancy, answerCorrectness（LLM-as-Judge，与生成解耦）</li>
 * </ul>
 */
public class RagEvalResult {

    private final String runId;
    private final String query;

    // 传统检索指标（ground-truth based）
    private double recallAt5;
    private double mrr;
    private double ndcgAt5;

    // RAGAS 检索质量（LLM evaluated）
    private double contextPrecision;
    private double contextRecall;
    private double contextRelevance;

    // RAGAS 生成质量（LLM evaluated，与生成解耦）
    private double faithfulness;
    private double answerRelevancy;
    private double answerCorrectness;

    private String evalMethod;
    private String generatedAnswer;
    private Map<String, Object> details = new LinkedHashMap<>();

    public RagEvalResult(String runId, String query) {
        this.runId = runId;
        this.query = query;
    }

    // ---- Fluent setters ----

    public RagEvalResult recallAt5(double v) { this.recallAt5 = clamp(v); return this; }
    public RagEvalResult mrr(double v) { this.mrr = clamp(v); return this; }
    public RagEvalResult ndcgAt5(double v) { this.ndcgAt5 = clamp(v); return this; }
    public RagEvalResult contextPrecision(double v) { this.contextPrecision = clamp(v); return this; }
    public RagEvalResult contextRecall(double v) { this.contextRecall = clamp(v); return this; }
    public RagEvalResult contextRelevance(double v) { this.contextRelevance = clamp(v); return this; }
    public RagEvalResult faithfulness(double v) { this.faithfulness = clamp(v); return this; }
    public RagEvalResult answerRelevancy(double v) { this.answerRelevancy = clamp(v); return this; }
    public RagEvalResult answerCorrectness(double v) { this.answerCorrectness = clamp(v); return this; }
    public RagEvalResult evalMethod(String v) { this.evalMethod = v; return this; }
    public RagEvalResult generatedAnswer(String v) { this.generatedAnswer = v; return this; }
    public RagEvalResult detail(String key, Object value) { this.details.put(key, value); return this; }

    /** RAGAS 综合得分: faithfulness, answerRelevancy, contextPrecision, contextRecall 的调和平均 */
    public double harmonicMean() {
        double sum = 0;
        int count = 0;
        if (faithfulness > 0) { sum += 1.0 / faithfulness; count++; }
        if (answerRelevancy > 0) { sum += 1.0 / answerRelevancy; count++; }
        if (contextPrecision > 0) { sum += 1.0 / contextPrecision; count++; }
        if (contextRecall > 0) { sum += 1.0 / contextRecall; count++; }
        return count == 0 ? 0 : count / sum;
    }

    public boolean passesQualityGate() {
        return faithfulness >= 0.6 && contextPrecision >= 0.6
                && contextRecall >= 0.6 && answerRelevancy >= 0.6;
    }

    // ---- Getters ----

    public String getRunId() { return runId; }
    public String getQuery() { return query; }
    public double getRecallAt5() { return recallAt5; }
    public double getMrr() { return mrr; }
    public double getNdcgAt5() { return ndcgAt5; }
    public double getContextPrecision() { return contextPrecision; }
    public double getContextRecall() { return contextRecall; }
    public double getContextRelevance() { return contextRelevance; }
    public double getFaithfulness() { return faithfulness; }
    public double getAnswerRelevancy() { return answerRelevancy; }
    public double getAnswerCorrectness() { return answerCorrectness; }
    public String getEvalMethod() { return evalMethod; }
    public String getGeneratedAnswer() { return generatedAnswer; }
    public Map<String, Object> getDetails() { return Map.copyOf(details); }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("runId", runId);
        map.put("query", query);
        map.put("recallAt5", recallAt5);
        map.put("mrr", mrr);
        map.put("ndcgAt5", ndcgAt5);
        map.put("contextPrecision", contextPrecision);
        map.put("contextRecall", contextRecall);
        map.put("contextRelevance", contextRelevance);
        map.put("faithfulness", faithfulness);
        map.put("answerRelevancy", answerRelevancy);
        map.put("answerCorrectness", answerCorrectness);
        map.put("harmonicMean", Math.round(harmonicMean() * 1000.0) / 1000.0);
        map.put("passesQualityGate", passesQualityGate());
        map.put("evalMethod", evalMethod);
        map.put("details", details);
        return map;
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
