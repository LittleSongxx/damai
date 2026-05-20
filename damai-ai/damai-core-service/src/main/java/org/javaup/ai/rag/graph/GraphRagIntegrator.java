package org.javaup.ai.rag.graph;

import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.ai.rag.MarkdownLoader;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class GraphRagIntegrator {

    private final KnowledgeGraphService graphService;
    private final MarkdownLoader markdownLoader;

    public GraphRagIntegrator(KnowledgeGraphService graphService, MarkdownLoader markdownLoader) {
        this.graphService = graphService;
        this.markdownLoader = markdownLoader;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            List<org.springframework.ai.document.Document> documents = loadDocuments();
            if (!documents.isEmpty()) {
                graphService.buildFromDocuments(documents);
                log.info("GraphRAG initialized: {} documents indexed", documents.size());
            }
        } catch (Exception e) {
            log.warn("GraphRAG initialization deferred: {}", e.getMessage());
        }
    }

    private List<org.springframework.ai.document.Document> loadDocuments() {
        try {
            MarkdownLoader.LoadResult loadResult = markdownLoader.loadMarkdownsWithMetadata();
            if (loadResult != null && loadResult.documents() != null) {
                return loadResult.documents();
            }
        } catch (Exception e) {
            log.warn("GraphRAG document loading failed: {}", e.getMessage());
        }
        return List.of();
    }

    public String enrichPrompt(String question, String basePrompt) {
        try {
            var paths = graphService.query(question);
            if (paths.isEmpty()) return basePrompt;

            String graphContext = graphService.formatGraphContext(paths);
            return graphContext + "\n---\n" + basePrompt;
        } catch (Exception e) {
            log.debug("GraphRAG enrichment skipped: {}", e.getMessage());
            return basePrompt;
        }
    }
}
