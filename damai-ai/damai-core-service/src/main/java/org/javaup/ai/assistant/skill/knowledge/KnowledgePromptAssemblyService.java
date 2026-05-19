package org.javaup.ai.assistant.skill.knowledge;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class KnowledgePromptAssemblyService {

    public KnowledgePromptAssemblyResult assemble(String userMessage, KnowledgeRetrievalContext retrievalContext) {
        ContextBlock contextBlock = buildContextBlockWithRefs(retrievalContext.answerDocuments(), retrievalContext.plan().evidenceContextCharBudget());
        String groundedPrompt = """
                你是大麦规则助手。只能基于给定证据回答，不能补充证据之外的规则。
                如果证据不足，必须明确说明无法确认。

                证据：
                %s

                用户问题：
                %s

                回答要求：
                1. 先给结论，再给依据。
                2. 引用证据时使用方括号标注来源编号，例如 [1]、[2]。
                3. 不要虚构未命中的规则。
                4. 不要输出"参考来源"或"参考文献"标题或列表，系统会单独展示证据卡片。
                """.formatted(contextBlock.value(), userMessage);
        return new KnowledgePromptAssemblyResult(contextBlock.value(), groundedPrompt,
                retrievalContext.plan().evidenceContextCharBudget(), contextBlock.renderedDocumentCount(),
                contextBlock.sourceRefs());
    }

    private ContextBlock buildContextBlockWithRefs(List<Document> answerDocuments, int contextCharBudget) {
        StringBuilder builder = new StringBuilder();
        Set<String> seen = new LinkedHashSet<>();
        Map<String, SourceRef> sourceRefs = new LinkedHashMap<>();
        int renderedDocumentCount = 0;
        int refIndex = 1;

        for (Document document : answerDocuments) {
            String text = document.getText();
            if (text == null || !seen.add(text)) {
                continue;
            }
            String refId = "[" + refIndex + "]";

            if (builder.length() > 0) {
                String separator = "\n\n---\n\n";
                if (builder.length() + separator.length() >= contextCharBudget) break;
                builder.append(separator);
            }

            int remaining = contextCharBudget - builder.length();
            if (remaining <= 0) break;

            String header = refId + " ";
            builder.append(header);
            int textBudget = remaining - header.length();
            if (textBudget <= 0) break;
            builder.append(text, 0, Math.min(text.length(), textBudget));

            String title = stringValue(document.getMetadata().get("docTitle"),
                    stringValue(document.getMetadata().get("title"), ""));
            String section = stringValue(document.getMetadata().get("headingPath"),
                    stringValue(document.getMetadata().get("section"), ""));
            String chunkId = stringValue(document.getMetadata().get("chunkId"), "");
            String source = stringValue(document.getMetadata().get("source"), "");

            sourceRefs.put(refId, new SourceRef(refId, title, section, chunkId, source));
            renderedDocumentCount++;
            refIndex++;
        }

        return new ContextBlock(builder.toString(), renderedDocumentCount, sourceRefs);
    }

    private static String stringValue(Object value, String fallback) {
        if (value == null) return fallback;
        String s = String.valueOf(value);
        return s.isBlank() ? fallback : s;
    }

    private record ContextBlock(String value, int renderedDocumentCount, Map<String, SourceRef> sourceRefs) {
    }
}
