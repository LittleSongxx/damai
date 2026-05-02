package org.javaup.ai.assistant.skill.knowledge;

import org.javaup.ai.vo.RagSourceVo;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class StructuredRuleSupportService {

    public SupportBundle lookup(String query) {
        List<Document> documents = new ArrayList<>();
        List<RagSourceVo> sources = new ArrayList<>();
        if (query.contains("退票") || query.contains("退款")) {
            add(documents, sources, "structured-refund",
                    "退票与退款规则提示",
                    "该问题属于退票、退款、取消订单类规则，回答前应优先检索退票 FAQ 与节目级退改条款。",
                    "structured_rule");
        }
        if (query.contains("实名") || query.contains("入场") || query.contains("儿童票")) {
            add(documents, sources, "structured-entry",
                    "实名与入场规则提示",
                    "该问题属于实名制、入场证件或儿童票规则，回答前应优先检索订票 FAQ 中的实名和入场条款。",
                    "structured_rule");
        }
        return new SupportBundle(documents, sources);
    }

    private void add(List<Document> documents, List<RagSourceVo> sources, String chunkId, String title, String text, String sourceType) {
        documents.add(new Document(text, Map.of(
                "chunkId", chunkId,
                "name", title,
                "title", title,
                "source", sourceType,
                "section", "retrieval-hint"
        )));
        sources.add(RagSourceVo.builder()
                .chunkId(chunkId)
                .title(title)
                .source(sourceType)
                .section("retrieval-hint")
                .snippet(text)
                .score(0.35D)
                .build());
    }

    public record SupportBundle(List<Document> documents, List<RagSourceVo> sources) {
    }
}
