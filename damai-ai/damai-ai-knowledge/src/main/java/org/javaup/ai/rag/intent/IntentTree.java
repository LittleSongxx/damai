package org.javaup.ai.rag.intent;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hardcoded intent tree for damai ticketing domain. Each leaf node maps to an
 * AssistantRouteType and includes example queries for LLM-based classification.
 */
@Component
public class IntentTree {

    private final List<IntentNode> roots;
    private final Map<String, IntentNode> nodeById = new LinkedHashMap<>();

    public IntentTree() {
        List<IntentNode> businessChildren = List.of(
                IntentNode.builder().id("business.program_search").name("节目查询").description("搜索演唱会、话剧、体育赛事")
                        .kind(IntentNode.IntentKind.KB).level(2).parentId("business")
                        .examples(List.of("张学友演唱会", "最近有什么话剧", "周末的篮球赛")).routeType("BUSINESS").build(),
                IntentNode.builder().id("business.ticket_purchase").name("购票下单").description("购买演出门票")
                        .kind(IntentNode.IntentKind.TOOL).level(2).parentId("business")
                        .examples(List.of("我要买票", "下单", "购买两张VIP票")).routeType("BUSINESS").build(),
                IntentNode.builder().id("business.order_query").name("订单查询").description("查询已有订单状态和详情")
                        .kind(IntentNode.IntentKind.TOOL).level(2).parentId("business")
                        .examples(List.of("我的订单在哪", "查订单", "订单状态")).routeType("BUSINESS").build()
        );
        List<IntentNode> knowledgeChildren = List.of(
                IntentNode.builder().id("knowledge.refund").name("退票规则").description("退票条件、手续费、到账时间")
                        .kind(IntentNode.IntentKind.KB).level(2).parentId("knowledge")
                        .examples(List.of("能退票吗", "退票手续费多少", "退款多久到账")).routeType("KNOWLEDGE").build(),
                IntentNode.builder().id("knowledge.entry").name("入场须知").description("证件要求、儿童票、安检规定")
                        .kind(IntentNode.IntentKind.KB).level(2).parentId("knowledge")
                        .examples(List.of("需要带什么证件", "儿童要买票吗", "可以带包吗")).routeType("KNOWLEDGE").build(),
                IntentNode.builder().id("knowledge.transfer").name("转赠规则").description("电子票转赠流程和限制")
                        .kind(IntentNode.IntentKind.KB).level(2).parentId("knowledge")
                        .examples(List.of("票能转给别人吗", "怎么转赠", "转赠需要实名吗")).routeType("KNOWLEDGE").build(),
                IntentNode.builder().id("knowledge.venue").name("场馆指南").description("场馆位置、座位图、交通指引")
                        .kind(IntentNode.IntentKind.KB).level(2).parentId("knowledge")
                        .examples(List.of("鸟巢怎么走", "场馆有停车位吗", "座位图")).routeType("KNOWLEDGE").build()
        );
        List<IntentNode> opsChildren = List.of(
                IntentNode.builder().id("ops.order_data").name("订单数据").description("订单量、GMV、支付成功率")
                        .kind(IntentNode.IntentKind.TOOL).level(2).parentId("ops")
                        .examples(List.of("今天的订单量", "本月GMV", "支付成功率")).routeType("OPS").build(),
                IntentNode.builder().id("ops.system_health").name("系统健康").description("JVM、CPU、GC、错误率监控")
                        .kind(IntentNode.IntentKind.TOOL).level(2).parentId("ops")
                        .examples(List.of("CPU使用率", "JVM内存", "接口错误率")).routeType("OPS").build()
        );
        List<IntentNode> generalChildren = List.of(
                IntentNode.builder().id("general.artist_info").name("艺人信息").description("歌手、乐队、演员的基本信息")
                        .kind(IntentNode.IntentKind.KB).level(2).parentId("general")
                        .examples(List.of("周杰伦是谁", "Coldplay成员", "刘德华多大了")).routeType("GENERAL").build(),
                IntentNode.builder().id("general.chitchat").name("闲聊").description("问候、感谢、道别等")
                        .kind(IntentNode.IntentKind.SYSTEM).level(2).parentId("general")
                        .examples(List.of("你好", "谢谢", "再见")).routeType("GENERAL").build()
        );

        roots = List.of(
                IntentNode.builder().id("business").name("票务业务").description("演出查询、购票、订单管理")
                        .kind(IntentNode.IntentKind.KB).level(1).children(businessChildren)
                        .examples(List.of("我想看演唱会", "买票", "查订单")).routeType("BUSINESS").build(),
                IntentNode.builder().id("knowledge").name("规则知识").description("退票、入场、转赠、场馆等平台规则")
                        .kind(IntentNode.IntentKind.KB).level(1).children(knowledgeChildren)
                        .examples(List.of("退票规则", "入场须知", "转赠流程")).routeType("KNOWLEDGE").build(),
                IntentNode.builder().id("ops").name("运维问数").description("运营数据查询、系统监控")
                        .kind(IntentNode.IntentKind.TOOL).level(1).children(opsChildren)
                        .examples(List.of("订单量统计", "GMV趋势", "系统错误率")).routeType("OPS").build(),
                IntentNode.builder().id("general").name("通用闲聊").description("艺人信息、联网搜索、日常对话")
                        .kind(IntentNode.IntentKind.SYSTEM).level(1).children(generalChildren)
                        .examples(List.of("谁是周杰伦", "你好")).routeType("GENERAL").build()
        );
        for (var root : roots) register(root);
    }

    private void register(IntentNode node) {
        nodeById.put(node.getId(), node);
        if (node.getChildren() != null) {
            for (var child : node.getChildren()) register(child);
        }
    }

    public List<IntentNode> getRoots() { return roots; }
    public Map<String, IntentNode> getNodeById() { return nodeById; }

    /** Collect all leaf nodes for LLM classification prompt. */
    public List<IntentNode> getLeaves() {
        List<IntentNode> leaves = new ArrayList<>();
        for (var root : roots) collectLeaves(root, leaves);
        return leaves;
    }

    private void collectLeaves(IntentNode node, List<IntentNode> leaves) {
        if (node.getChildren() == null || node.getChildren().isEmpty()) leaves.add(node);
        else for (var child : node.getChildren()) collectLeaves(child, leaves);
    }
}
