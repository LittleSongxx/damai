package org.javaup.ai.rag.intent;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Tree-structured intent node for hierarchical intent classification.
 * Supports three node kinds: KB (knowledge base), TOOL (function calling), SYSTEM (direct answer).
 *
 * Inspired by ragent's IntentNode taxonomy.
 */
@Data
@Builder
public class IntentNode {
    private String id;
    private String name;
    private String description;
    private IntentKind kind;             // KB, TOOL, SYSTEM
    private int level;                   // 0=DOMAIN, 1=CATEGORY, 2=TOPIC
    private String parentId;
    private List<String> examples;
    private List<IntentNode> children;
    private String routeType;            // maps to AssistantRouteType code

    /** Computed full path, e.g. "票务 > 购票 > 演唱会" */
    public String fullPath() {
        return name;
    }

    public boolean isLeaf() {
        return children == null || children.isEmpty();
    }

    public enum IntentKind { KB, TOOL, SYSTEM }
}
