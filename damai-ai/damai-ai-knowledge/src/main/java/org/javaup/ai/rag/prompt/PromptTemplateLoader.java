package org.javaup.ai.rag.prompt;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads .st prompt template files from classpath and provides Mustache-style
 * variable substitution via {{key}} placeholders.
 *
 * Inspired by ragent's PromptTemplateLoader.
 */
@Slf4j
@Component
public class PromptTemplateLoader {

    private final Map<String, String> templates = new ConcurrentHashMap<>();
    private final ResourcePatternResolver resolver;

    public PromptTemplateLoader(ResourcePatternResolver resolver) {
        this.resolver = resolver;
        loadAll();
    }

    private void loadAll() {
        try {
            Resource[] resources = resolver.getResources("classpath:prompt/*.st");
            for (Resource resource : resources) {
                String filename = resource.getFilename();
                if (filename != null) {
                    String content = resource.getContentAsString(StandardCharsets.UTF_8);
                    templates.put(filename, content);
                    log.debug("Loaded prompt template: {}", filename);
                }
            }
            log.info("Loaded {} prompt templates", templates.size());
        } catch (IOException e) {
            log.warn("Failed to load prompt templates: {}", e.getMessage());
        }
    }

    public String render(String templateName, Map<String, String> variables) {
        String template = templates.get(templateName);
        if (template == null) {
            throw new IllegalArgumentException("Template not found: " + templateName);
        }
        String result = template;
        for (var entry : variables.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}",
                    entry.getValue() != null ? entry.getValue() : "");
        }
        return result;
    }

    public boolean hasTemplate(String templateName) {
        return templates.containsKey(templateName);
    }

    public String getRaw(String templateName) {
        return templates.get(templateName);
    }
}
