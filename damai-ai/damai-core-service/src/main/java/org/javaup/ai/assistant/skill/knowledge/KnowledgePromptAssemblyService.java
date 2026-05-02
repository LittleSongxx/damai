package org.javaup.ai.assistant.skill.knowledge;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class KnowledgePromptAssemblyService {

    public KnowledgePromptAssemblyResult assemble(String userMessage, KnowledgeRetrievalContext retrievalContext) {
        ContextBlock contextBlock = buildContextBlock(retrievalContext.answerDocuments(), retrievalContext.plan().evidenceContextCharBudget());
        String groundedPrompt = """
                你是大麦规则助手。只能基于给定证据回答，不能补充证据之外的规则。
                如果证据不足，必须明确说明无法确认。

                证据：
                %s

                用户问题：
                %s

                回答要求：
                1. 先给结论，再给依据。
                2. 不要虚构未命中的规则。
                3. 不要输出“参考来源”标题，系统会单独展示证据卡片。
                """.formatted(contextBlock.value(), userMessage);
        return new KnowledgePromptAssemblyResult(contextBlock.value(), groundedPrompt, retrievalContext.plan().evidenceContextCharBudget(), contextBlock.renderedDocumentCount());
    }

    private ContextBlock buildContextBlock(List<Document> answerDocuments, int contextCharBudget) {
        StringBuilder builder = new StringBuilder();
        Set<String> seen = new LinkedHashSet<>();
        int renderedDocumentCount = 0;
        for (Document document : answerDocuments) {
            String text = document.getText();
            if (text == null || !seen.add(text)) {
                continue;
            }
            if (builder.length() > 0) {
                String separator = "\n\n---\n\n";
                if (builder.length() + separator.length() >= contextCharBudget) {
                    break;
                }
                builder.append(separator);
            }
            int remaining = contextCharBudget - builder.length();
            if (remaining <= 0) {
                break;
            }
            builder.append(text, 0, Math.min(text.length(), remaining));
            renderedDocumentCount++;
        }
        return new ContextBlock(builder.toString(), renderedDocumentCount);
    }

    private record ContextBlock(String value, int renderedDocumentCount) {
    }
}
