package org.javaup.ai.assistant.skill.knowledge;

import org.javaup.ai.vo.RagSourceVo;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class StructuredRuleSupportService {

    private static final List<RuleEntry> RULE_ENTRIES = List.of(
            new RuleEntry(List.of("退票", "退款", "取消订单"), "structured-refund",
                    "退票与退款规则提示",
                    "该问题属于退票、退款、取消订单类规则，回答前应优先检索退票 FAQ 与节目级退改条款。"),
            new RuleEntry(List.of("实名", "入场", "儿童票", "观演人"), "structured-entry",
                    "实名与入场规则提示",
                    "该问题属于实名制、入场证件或儿童票规则，回答前应优先检索订票 FAQ 中的实名和入场条款。"),
            new RuleEntry(List.of("电子票", "数字票", "转赠", "票夹"), "structured-eticket",
                    "电子票与数字票规则提示",
                    "该问题涉及电子票、数字票或转赠规则，回答前应优先检索电子票/数字票 FAQ 条款。"),
            new RuleEntry(List.of("支付", "付款", "超时", "重复支付"), "structured-payment",
                    "订单支付规则提示",
                    "该问题涉及订单支付、超时或重复支付，回答前应优先检索支付与订单 FAQ 条款。"),
            new RuleEntry(List.of("配送", "快递", "取票", "自取"), "structured-delivery",
                    "配送与取票规则提示",
                    "该问题涉及票务配送或现场取票，回答前应优先检索配送与取票 FAQ 条款。"),
            new RuleEntry(List.of("安检", "禁带", "违禁品"), "structured-security",
                    "入场安检规则提示",
                    "该问题涉及入场安检或禁带物品，回答前应优先检索入场安检 FAQ 条款。"),
            new RuleEntry(List.of("限购", "抢票", "排队"), "structured-purchase-limit",
                    "购票限制规则提示",
                    "该问题涉及购票数量限制或排队规则，回答前应优先检索购票限制 FAQ 条款。"),
            new RuleEntry(List.of("防诈骗", "非官方", "私下交易", "验证码"), "structured-safety",
                    "交易安全规则提示",
                    "该问题涉及交易安全或防诈骗，回答前应优先检索交易安全 FAQ 条款。")
    );

    public SupportBundle lookup(String query) {
        List<Document> documents = new ArrayList<>();
        List<RagSourceVo> sources = new ArrayList<>();
        String lower = query.toLowerCase();
        for (RuleEntry entry : RULE_ENTRIES) {
            if (entry.keywords.stream().anyMatch(lower::contains)) {
                add(documents, sources, entry.chunkId, entry.title, entry.text, "structured_rule");
            }
        }
        return new SupportBundle(documents, sources);
    }

    private record RuleEntry(List<String> keywords, String chunkId, String title, String text) {}

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
