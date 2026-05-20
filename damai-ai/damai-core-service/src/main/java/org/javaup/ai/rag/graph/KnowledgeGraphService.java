package org.javaup.ai.rag.graph;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class KnowledgeGraphService {

    private final Map<String, EntityNode> entities = new ConcurrentHashMap<>();
    private final List<Relation> relations = new ArrayList<>();

    public void buildFromDocuments(List<Document> documents) {
        if (documents == null) return;
        for (Document doc : documents) {
            extractEntities(doc);
        }
        linkEntities();
        log.info("Knowledge graph built: {} entities, {} relations", entities.size(), relations.size());
    }

    private void extractEntities(Document doc) {
        String text = doc.getText();
        if (!StringUtils.hasText(text)) return;

        String docTitle = doc.getMetadata().getOrDefault("docTitle", "").toString();
        extractMatch(text, docTitle, EntityType.VENUE, "国家体育场|鸟巢|工人体育场|五棵松|梅赛德斯|上海体育场|广州体育馆");
        extractMatch(text, docTitle, EntityType.ARTIST, "周杰伦|五月天|陈奕迅|张学友|刘德华|林俊杰|邓紫棋");
        extractMatch(text, docTitle, EntityType.POLICY, "退票|退款|改签|转赠|实名制|儿童票|电子票|安检");
        extractMatch(text, docTitle, EntityType.PROGRAM_TYPE, "演唱会|话剧|脱口秀|音乐节|歌剧|芭蕾|音乐会|展览");
        extractMatch(text, docTitle, EntityType.TICKET_TIER, "VIP|看台|内场|站票|座票|包厢");
    }

    private void extractMatch(String text, String docTitle, EntityType type, String pattern) {
        Matcher matcher = Pattern.compile(pattern).matcher(text);
        while (matcher.find()) {
            String name = matcher.group();
            String key = type.name().toLowerCase() + ":" + name;
            entities.computeIfAbsent(key, k -> new EntityNode(name, type, docTitle));
        }
    }

    private void linkEntities() {
        relations.clear();
        for (EntityNode entity : entities.values()) {
            for (EntityNode other : entities.values()) {
                if (entity == other) continue;
                if (entity.docTitle().equals(other.docTitle())) {
                    relations.add(new Relation(entity.name(), entity.type(), other.name(), other.type(), "co_occur"));
                }
            }
        }
    }

    public List<GraphPath> query(String question) {
        List<GraphPath> paths = new ArrayList<>();
        for (EntityNode entity : entities.values()) {
            if (question.contains(entity.name())) {
                List<Relation> outgoing = relations.stream()
                        .filter(r -> r.from().equals(entity.name()) || r.to().equals(entity.name()))
                        .filter(r -> !r.from().equals(r.to()))
                        .limit(5)
                        .toList();
                if (!outgoing.isEmpty()) {
                    paths.add(new GraphPath(entity, outgoing));
                }
            }
        }
        return paths;
    }

    public String formatGraphContext(List<GraphPath> paths) {
        if (paths == null || paths.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("【知识图谱关联信息】\n");
        for (GraphPath path : paths) {
            sb.append("- ").append(path.entity().name())
                    .append("（").append(path.entity().type().getLabel()).append("）");
            for (Relation rel : path.relations()) {
                String target = rel.from().equals(path.entity().name()) ? rel.to() : rel.from();
                sb.append(" → ").append(target)
                        .append("（关系：").append(rel.type()).append("）");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    public Map<String, Object> getStats() {
        return Map.of("entities", entities.size(), "relations", relations.size());
    }

    public record EntityNode(String name, EntityType type, String docTitle) {}

    public record Relation(String from, EntityType fromType, String to, EntityType toType, String type) {}

    public record GraphPath(EntityNode entity, List<Relation> relations) {}

    public enum EntityType {
        VENUE("场馆"), ARTIST("艺人"), POLICY("政策规则"),
        PROGRAM_TYPE("演出类型"), TICKET_TIER("票档");

        private final String label;

        EntityType(String label) { this.label = label; }
        public String getLabel() { return label; }
    }
}
