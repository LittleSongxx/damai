package org.javaup.ai.assistant.eval;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG 离线评估打分器 —— 严格遵循 RAGAS (Retrieval Augmented Generation Assessment) 体系.
 *
 * <p>通过直接 HTTP 调用 DeepSeek API (OpenAI 兼容模式)，绕过 Spring AI ChatClient，
 * 避免 Spring AI OpenAiApi.ChatCompletion 反序列化与 DeepSeek 响应的兼容性问题。</p>
 *
 * <h2>评测指标体系</h2>
 *
 * <h3>第一类: 传统检索指标（基于标注 ground-truth chunk ID 匹配，无需 LLM）</h3>
 * <ul>
 *   <li><b>Recall@K</b>: expected_chunks 中有多少出现在 retrieved_chunks 的 Top-K</li>
 *   <li><b>MRR (Mean Reciprocal Rank)</b>: 第一个相关 chunk 的排名倒数</li>
 *   <li><b>NDCG@K</b>: 归一化折损累计增益</li>
 * </ul>
 *
 * <h3>第二类: RAGAS 检索质量指标（LLM-as-Judge）</h3>
 * <ul>
 *   <li><b>Context Precision</b>: LLM 逐条判断每个检索文档是否与问题相关</li>
 *   <li><b>Context Recall</b>: LLM 从参考答案提取关键信息点，判断能否在检索上下文中找到</li>
 *   <li><b>Context Relevance</b>: LLM 判断检索内容中与问题直接相关的句子比例</li>
 * </ul>
 *
 * <h3>第三类: RAGAS 生成质量指标（LLM-as-Judge）</h3>
 * <ul>
 *   <li><b>Faithfulness</b>: 将生成答案分解为原子声明，逐条验证是否被上下文支撑</li>
 *   <li><b>Answer Relevancy</b>: 答案是否直接、完整地回应了用户问题</li>
 *   <li><b>Answer Correctness</b>: 生成答案与人工标注参考答案的对比</li>
 * </ul>
 *
 * @see <a href="https://arxiv.org/abs/2309.15217">RAGAS: Automated Evaluation of Retrieval Augmented Generation</a>
 */
@Slf4j
@Service
public class RagEvalScorer {

    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    @Value("${spring.ai.openai.chat.options.model:deepseek-v4-pro}")
    private String model;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    // ==================== LLM 调用封装 ====================

    /**
     * 直接通过 HTTP 调用 DeepSeek API (OpenAI 兼容格式)，返回 message.content。
     * 绕过 Spring AI 的 ChatClient，避免 OpenAiApi.ChatCompletion 反序列化兼容问题。
     */
    private String callLlm(String systemPrompt, String userPrompt) {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("temperature", 0.1);
        body.put("max_tokens", 4096);

        JSONArray messages = new JSONArray();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            JSONObject sysMsg = new JSONObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", systemPrompt);
            messages.add(sysMsg);
        }
        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", userPrompt);
        messages.add(userMsg);
        body.put("messages", messages);

        String url = baseUrl.replaceAll("/+$", "") + "/v1/chat/completions";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toJSONString()))
                .timeout(Duration.ofSeconds(180))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                String preview = response.body();
                if (preview != null && preview.length() > 500) {
                    preview = preview.substring(0, 500);
                }
                log.error("LLM API returned {}: {}", response.statusCode(), preview);
                throw new RuntimeException("LLM API error: " + response.statusCode());
            }
            JSONObject result = JSON.parseObject(response.body());
            JSONArray choices = result.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                log.warn("LLM returned empty choices");
                return "";
            }
            JSONObject message = choices.getJSONObject(0).getJSONObject("message");
            String content = message != null ? message.getString("content") : "";
            return content != null ? content.trim() : "";
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("LLM API call failed: {}", e.toString());
            throw new RuntimeException("LLM API call failed", e);
        }
    }

    // ==================== Step 1: 生成答案（仅基于检索上下文，不给参考答案） ====================

    public String generateAnswer(String question, List<Document> retrievedDocs) {
        if (retrievedDocs == null || retrievedDocs.isEmpty()) {
            return "（未检索到相关文档）";
        }
        String context = retrievedDocs.stream()
                .filter(d -> d.getText() != null)
                .map(Document::getText)
                .collect(Collectors.joining("\n---\n"));
        if (context.isBlank()) {
            return "（未检索到相关文档）";
        }

        String prompt = String.format("""
                你是一个票务客服助手。请仅基于以下检索到的文档回答问题。
                如果文档中没有相关信息，请如实说明"文档中未找到相关信息"。
                不要编造任何文档中没有的内容。

                【用户问题】%s

                【检索文档】
                %s

                请直接回答用户的问题：""", question, context);

        try {
            String raw = callLlm(null, prompt);
            return !raw.isBlank() ? raw : "（生成失败）";
        } catch (Exception e) {
            log.warn("Answer generation failed: {}", e.getMessage());
            return "（生成失败: " + e.getMessage() + "）";
        }
    }

    // ==================== Step 2: 检索质量评估（RAGAS Context 指标） ====================

    public ContextEvalResult evaluateContext(String question, String expectedAnswer,
                                              List<Document> retrievedDocs) {
        if (retrievedDocs == null || retrievedDocs.isEmpty()) {
            return new ContextEvalResult(0, 0, 0);
        }

        StringBuilder docsBlock = new StringBuilder();
        List<String> docTexts = new ArrayList<>();
        for (int i = 0; i < retrievedDocs.size(); i++) {
            Document doc = retrievedDocs.get(i);
            String text = doc.getText() != null ? doc.getText() : "";
            docTexts.add(text);
            docsBlock.append("[").append(i + 1).append("] ").append(truncate(text, 500)).append("\n\n");
        }

        String contextFull = String.join("\n---\n", docTexts);

        String prompt = String.format("""
                你是一个RAG系统检索质量评估专家。请严格按以下要求评估，输出JSON。

                【用户问题】%s

                【参考答案（人工标注）】%s

                【检索到的文档】%s

                【检索到的文档全文】%s

                请完成以下三项评估，输出JSON格式（不要Markdown包裹）：
                {
                  "doc_relevance": [true, false, ...],
                  "context_precision_score": 0.0-1.0,
                  "key_info_points": ["信息点1", "信息点2", ...],
                  "covered_points": [true, false, ...],
                  "context_recall_score": 0.0-1.0,
                  "relevant_sentence_ratio": 0.0-1.0,
                  "context_relevance_score": 0.0-1.0
                }

                注意：
                - context_precision_score: 对每个标记为相关的文档，用1/log(rank+2)加权求和后归一化
                - context_recall_score: 如果参考答案的所有关键信息点都能在检索文档中找到，则为1.0
                - context_relevance_score: 检索内容中与回答问题直接相关的句子数/总句子数
                """, question, expectedAnswer != null ? expectedAnswer : "（无参考答案）",
                docsBlock.toString(), truncate(contextFull, 3000));

        try {
            String raw = callLlm(null, prompt);
            if (raw == null || raw.isBlank()) return new ContextEvalResult(0, 0, 0);

            String jsonStr = raw.replaceAll("```\\w*\\n?", "").replaceAll("```", "").trim();
            JSONObject json = JSON.parseObject(jsonStr);

            double cp = json.getDoubleValue("context_precision_score");
            double cr = json.getDoubleValue("context_recall_score");
            double crel = json.getDoubleValue("context_relevance_score");

            return new ContextEvalResult(clamp(cp), clamp(cr), clamp(crel));
        } catch (Exception e) {
            log.warn("Context evaluation failed: {}", e.getMessage());
            return new ContextEvalResult(0, 0, 0);
        }
    }

    public record ContextEvalResult(double contextPrecision, double contextRecall, double contextRelevance) {}

    // ==================== Step 3: 生成质量评估（Faithfulness + Answer Relevancy + Answer Correctness） ====================

    public GenEvalResult evaluateGeneration(String question, String generatedAnswer,
                                             String expectedAnswer, List<Document> retrievedDocs) {
        if (generatedAnswer == null || generatedAnswer.isBlank()) {
            return new GenEvalResult(0, 0, 0);
        }

        String context = retrievedDocs != null ? retrievedDocs.stream()
                .filter(d -> d.getText() != null)
                .map(Document::getText)
                .collect(Collectors.joining("\n---\n")) : "";

        String prompt = String.format("""
                你是一个RAG系统生成质量评估专家。请严格按以下要求评估，输出JSON。

                【用户问题】%s

                【检索上下文（证据）】%s

                【生成答案（仅基于上下文生成，未看参考答案）】%s

                【参考答案（人工标注，用于正确性对比）】%s

                请完成以下三项评估，输出JSON格式（不要Markdown包裹）：
                {
                  "claims": ["声明1", "声明2", ...],
                  "claims_supported": [true, false, ...],
                  "faithfulness_score": 0.0-1.0,
                  "faithfulness_reason": "简要说明哪些声明无支撑及其原因",

                  "answer_relevancy_score": 0.0-1.0,
                  "relevancy_reason": "答案是否直接回应了问题",

                  "factual_accuracy": 0.0-1.0,
                  "semantic_completeness": 0.0-1.0,
                  "answer_correctness_score": 0.0-1.0,
                  "correctness_reason": "与参考答案对比的关键差异"
                }

                评估标准：
                - faithfulness: 将答案拆分为原子声明(atomic claims)。每个声明如果能在【检索上下文】中找到直接支撑则标记为true，
                  需要推断的、上下文没有的、编造的都标记为false。faithfulness_score = 被支撑的声明数/总声明数。
                - answer_relevancy: 答案是否直接、贴切地回应了问题。
                - answer_correctness: 对比生成答案和参考答案的事实准确性和语义完整性。
                  answer_correctness_score = (factual_accuracy + semantic_completeness) / 2。
                """, question, truncate(context, 3000), truncate(generatedAnswer, 2000),
                expectedAnswer != null ? expectedAnswer : "（无参考答案）");

        try {
            String raw = callLlm(null, prompt);
            if (raw == null || raw.isBlank()) return new GenEvalResult(0, 0, 0);

            String jsonStr = raw.replaceAll("```\\w*\\n?", "").replaceAll("```", "").trim();
            JSONObject json = JSON.parseObject(jsonStr);

            double faith = json.getDoubleValue("faithfulness_score");
            double ar = json.getDoubleValue("answer_relevancy_score");
            double ac = json.getDoubleValue("answer_correctness_score");

            if (ac == 0) {
                double fa = json.getDoubleValue("factual_accuracy");
                double sc = json.getDoubleValue("semantic_completeness");
                if (fa > 0 || sc > 0) {
                    ac = (fa + sc) / 2.0;
                }
            }

            return new GenEvalResult(clamp(faith), clamp(ar), clamp(ac));
        } catch (Exception e) {
            log.warn("Generation evaluation failed: {}", e.getMessage());
            return new GenEvalResult(0, 0, 0);
        }
    }

    public record GenEvalResult(double faithfulness, double answerRelevancy, double answerCorrectness) {}

    // ==================== 快速启发式评分（降级方案） ====================

    public double computeContextRecallHeuristic(List<Document> retrievedDocs, List<String> referenceTexts) {
        if (referenceTexts == null || referenceTexts.isEmpty()) return 0;
        java.util.Set<String> retrievedTexts = retrievedDocs.stream()
                .filter(d -> d.getText() != null)
                .map(Document::getText)
                .collect(Collectors.toSet());
        int matched = 0;
        for (String ref : referenceTexts) {
            java.util.Set<String> refTokens = tokenize(ref);
            for (String retrieved : retrievedTexts) {
                java.util.Set<String> retTokens = tokenize(retrieved);
                long overlap = refTokens.stream().filter(retTokens::contains).count();
                if (!refTokens.isEmpty() && (double) overlap / refTokens.size() > 0.3) {
                    matched++;
                    break;
                }
            }
        }
        return (double) matched / referenceTexts.size();
    }

    private java.util.Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) return java.util.Set.of();
        java.util.Set<String> tokens = new java.util.LinkedHashSet<>();
        String cleaned = text.replaceAll("[\\p{P}\\s]+", " ").trim().toLowerCase();
        for (String word : cleaned.split("[ ,，、。！？；：\\n\\t]+")) {
            if (word.length() >= 1) tokens.add(word);
        }
        for (int i = 0; i < cleaned.length() - 1; i++) {
            String bigram = cleaned.substring(i, i + 2);
            if (bigram.codePoints().allMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)) {
                tokens.add(bigram);
            }
        }
        return tokens;
    }

    // ==================== 工具方法 ====================

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
