package org.javaup.ai.service;

import lombok.RequiredArgsConstructor;
import org.javaup.ai.ai.rag.MarkdownLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class KnowledgeIndexVersionService {

    private final MarkdownLoader markdownLoader;
    private final DocumentIngestionService documentIngestionService;

    public String currentVersion() {
        String indexVersion = markdownLoader.getCurrentIndexVersion();
        if (StringUtils.hasText(indexVersion)) {
            return indexVersion;
        }
        return StringUtils.hasText(documentIngestionService.qdrantSearchAlias())
                ? documentIngestionService.qdrantSearchAlias()
                : "unknown";
    }
}
