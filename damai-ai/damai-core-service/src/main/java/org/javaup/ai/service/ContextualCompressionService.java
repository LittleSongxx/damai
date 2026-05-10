package org.javaup.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @program: 大麦-ai智能服务项目。 添加 阿星不是程序员 微信，添加时备注 ai 来获取项目的完整资料
 * @description: 上下文压缩服务 - 从检索到的文档中提取与查询相关的核心信息，减少噪音
 * @author: 阿星不是程序员
 **/
@Slf4j
@Service
public class ContextualCompressionService {

    private final ChatClient chatClient;

    public ContextualCompressionService(@Qualifier("unifiedKnowledgeChatClient") ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    /**
     * 对检索到的文档进行上下文压缩，只保留与查询相关的核心内容。
     * 过滤掉不相关的段落，压缩冗长的描述，保留关键事实和规则。
     */
    public List<Document> compress(String query, List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return documents;
        }
        try {
            return compressWithLLM(query, documents);
        } catch (Exception e) {
            log.warn("LLM 上下文压缩失败，使用截断压缩降级", e);
            return compressByTruncation(documents);
        }
    }

    /**
     * 使用 LLM 进行智能上下文压缩。LLM 会判断每段文档中哪些部分与查询相关，
     * 去除无关内容，只保留能回答问题的关键信息。
     */
    public List<Document> compressWithLLM(String query, List<Document> documents) {
        StringBuilder docList = new StringBuilder();
        int maxChars = 500;
        for (int i = 0; i < documents.size(); i++) {
            String text = documents.get(i).getText();
            if (text != null) {
                String snippet = text.length() > maxChars ? text.substring(0, maxChars) + "..." : text;
                docList.append(String.format("[DOC_%d]\n%s\n\n", i + 1, snippet));
            }
        }

        String prompt = """
                你是信息提取器。请从以下文档中提取与用户问题直接相关的关键信息。
                
                规则：
                1. 对每个文档，只保留与问题相关的核心句子和关键事实
                2. 如果某个文档完全不相关，输出 [DOC_N] 无关
                3. 保留原文措辞，不要改写，只做删减
                4. 每个文档的压缩结果用 [DOC_N] 开头，换行后输出压缩内容
                
                用户问题：%s
                
                文档：
                %s
                
                压缩结果：
                """.formatted(query, docList.toString());

        String result = chatClient.prompt()
                .user(prompt)
                .call()
                .content();

        return parseCompressedDocuments(result, documents);
    }

    /**
     * 降级方案：简单截断压缩，保留每个文档的前 N 个字符。
     */
    public List<Document> compressByTruncation(List<Document> documents) {
        int maxLength = 400;
        List<Document> compressed = new ArrayList<>();
        for (Document doc : documents) {
            String text = doc.getText();
            if (text != null && text.length() > maxLength) {
                Map<String, Object> metadata = new HashMap<>(doc.getMetadata());
                metadata.put("compressed", true);
                metadata.put("originalLength", text.length());
                compressed.add(new Document(text.substring(0, maxLength), metadata));
            } else {
                compressed.add(doc);
            }
        }
        return compressed;
    }

    private List<Document> parseCompressedDocuments(String result, List<Document> originalDocuments) {
        if (!StringUtils.hasText(result)) {
            return originalDocuments;
        }
        List<Document> compressed = new ArrayList<>();
        String[] sections = result.split("\\[DOC_");
        for (String section : sections) {
            if (!StringUtils.hasText(section.trim())) {
                continue;
            }
            try {
                int bracketEnd = section.indexOf(']');
                if (bracketEnd <= 0) {
                    continue;
                }
                int docIndex = Integer.parseInt(section.substring(0, bracketEnd).trim()) - 1;
                String content = section.substring(bracketEnd + 1).trim();
                if (docIndex < 0 || docIndex >= originalDocuments.size()) {
                    continue;
                }
                if (content.contains("无关") && content.length() < 10) {
                    continue;
                }
                Map<String, Object> metadata = new HashMap<>(originalDocuments.get(docIndex).getMetadata());
                metadata.put("compressed", true);
                metadata.put("originalLength", originalDocuments.get(docIndex).getText() == null ? 0 : originalDocuments.get(docIndex).getText().length());
                compressed.add(new Document(content, metadata));
            } catch (NumberFormatException ignored) {
            }
        }
        if (compressed.isEmpty()) {
            log.warn("上下文压缩解析结果为空，返回原始文档");
            return originalDocuments;
        }
        log.info("上下文压缩完成：{}个文档 -> {}个相关文档", originalDocuments.size(), compressed.size());
        return compressed;
    }
}
