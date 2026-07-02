package org.javaup.ai.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.dto.CustomerHandoffRequest;
import org.javaup.ai.dto.CustomerQuickAnswerRequest;
import org.javaup.ai.entity.AiCustomerHotQuestion;
import org.javaup.ai.entity.CustomerWorkItem;
import org.javaup.ai.enums.CustomerServiceIntent;
import org.javaup.ai.mapper.AiCustomerHotQuestionMapper;
import org.javaup.ai.vo.CustomerHotQuestionVo;
import org.javaup.ai.vo.CustomerQuickAnswerResponse;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class CustomerHotQuestionService {

    private static final String SCENE = "customer_service";
    private static final String CACHED_ANSWER = "CACHED_ANSWER";
    private static final String RUN_ASSISTANT = "RUN_ASSISTANT";

    private final AiCustomerHotQuestionMapper hotQuestionMapper;
    private final SentimentAnalysisService sentimentAnalysisService;
    private final CustomerIntentResolver intentResolver;
    private final CustomerWorkItemService workItemService;
    private final CustomerServiceMetricsService metricsService;

    public List<CustomerHotQuestionVo> starterPrompts() {
        List<HotQuestionDefinition> definitions = activeDefinitions();
        return definitions.stream()
                .sorted(Comparator.comparingInt(HotQuestionDefinition::priority))
                .map(this::toVo)
                .toList();
    }

    public CustomerQuickAnswerResponse quickAnswer(CustomerQuickAnswerRequest request, Long userId) {
        long startedAt = System.currentTimeMillis();
        String message = StringUtils.hasText(request.getMessage()) ? request.getMessage().trim() : "";
        CustomerServiceIntent intent = intentResolver.resolve(request, message);
        SentimentAnalysisService.SentimentResult sentiment =
                sentimentAnalysisService.quickAnalyze(message, null, request.getChatId(), userId);

        HotQuestionDefinition matched = findMatchedDefinition(request, message, intent);
        long latency = System.currentTimeMillis() - startedAt;
        Map<String, Object> dimensions = metricDimensions(request, message, intent, matched);
        if (matched != null && CACHED_ANSWER.equals(matched.answerMode())) {
            metricsService.record(null, request.getChatId(), userId,
                    CustomerServiceMetricsService.QUICK_ANSWER_HIT, 1D, latency, dimensions);
            metricsService.record(null, request.getChatId(), userId,
                    CustomerServiceMetricsService.CACHE_HIT, 1D, latency, dimensions);
            metricsService.record(null, request.getChatId(), userId,
                    CustomerServiceMetricsService.FIRST_RESPONSE_LATENCY, 1D, latency, dimensions);
            if ("NEGATIVE".equals(sentiment.sentiment())) {
                metricsService.record(null, request.getChatId(), userId,
                        CustomerServiceMetricsService.NEGATIVE_SENTIMENT, 1D, latency, dimensions);
            }
            CustomerWorkItem workItem = createWorkItemIfNeeded(request, userId, message, matched, sentiment);
            if (workItem != null) {
                metricsService.record(null, request.getChatId(), userId,
                        CustomerServiceMetricsService.WORK_ITEM_CREATED, 1D, latency, dimensions);
            }
            return CustomerQuickAnswerResponse.builder()
                    .hit(true)
                    .answerMode(CACHED_ANSWER)
                    .hotQuestionId(matched.questionId())
                    .intentCode(matched.intent().name())
                    .routeHint(matched.routeHint())
                    .directAnswer(withComfortPrefix(matched.directAnswer(), sentiment))
                    .actionButtons(actionButtons(matched, workItem))
                    .sourceRefs(matched.sourceRefs())
                    .suggestions(suggestions(matched.questionId()))
                    .clientContext(clientContext(request, matched.intent(), matched.questionId(), matched.routeHint()))
                    .sentiment(sentimentPayload(sentiment))
                    .workItem(workItem)
                    .latencyMs(latency)
                    .build();
        }

        metricsService.record(null, request.getChatId(), userId,
                CustomerServiceMetricsService.QUICK_ANSWER_MISS, 1D, latency, dimensions);
        if ("NEGATIVE".equals(sentiment.sentiment())) {
            metricsService.record(null, request.getChatId(), userId,
                    CustomerServiceMetricsService.NEGATIVE_SENTIMENT, 1D, latency, dimensions);
        }
        return CustomerQuickAnswerResponse.builder()
                .hit(false)
                .answerMode(RUN_ASSISTANT)
                .intentCode(intent.name())
                .routeHint(routeHint(intent))
                .actionButtons(clarificationButtons(intent))
                .suggestions(suggestions(null))
                .clientContext(clientContext(request, intent, request.getHotQuestionId(), routeHint(intent)))
                .sentiment(sentimentPayload(sentiment))
                .latencyMs(latency)
                .build();
    }

    private CustomerWorkItem createWorkItemIfNeeded(CustomerQuickAnswerRequest request,
                                                   Long userId,
                                                   String message,
                                                   HotQuestionDefinition matched,
                                                   SentimentAnalysisService.SentimentResult sentiment) {
        boolean needsHandoff = CustomerServiceIntent.HUMAN_HANDOFF.equals(matched.intent());
        boolean complaint = CustomerServiceIntent.COMPLAINT.equals(matched.intent());
        if (!needsHandoff && !complaint && !sentiment.shouldEscalate()) {
            return null;
        }
        CustomerHandoffRequest handoffRequest = new CustomerHandoffRequest();
        handoffRequest.setConversationId(request.getChatId());
        handoffRequest.setUserQuestion(message);
        handoffRequest.setAiAnswer(matched.directAnswer());
        handoffRequest.setSentiment(sentiment.sentiment());
        handoffRequest.setSentimentIntensity(sentiment.intensity());
        handoffRequest.setIntentCode(matched.intent().name());
        handoffRequest.setSourceRefs(matched.sourceRefs());
        handoffRequest.setBusinessContext(businessContext(request));
        handoffRequest.setReason(sentiment.escalationReason() != null ? sentiment.escalationReason() : "用户请求转人工或售后协助");
        handoffRequest.setSuggestedReply("请先安抚用户，确认订单号、场次和票档，再依据平台规则给出可执行的售后路径。");
        return workItemService.handoff(handoffRequest, userId);
    }

    private List<HotQuestionDefinition> activeDefinitions() {
        List<AiCustomerHotQuestion> rows;
        try {
            rows = hotQuestionMapper.selectList(Wrappers.lambdaQuery(AiCustomerHotQuestion.class)
                    .eq(AiCustomerHotQuestion::getScene, SCENE)
                    .eq(AiCustomerHotQuestion::getEnabled, 1)
                    .eq(AiCustomerHotQuestion::getStatus, 1)
                    .and(wrapper -> wrapper.isNull(AiCustomerHotQuestion::getExpireAt)
                            .or()
                            .gt(AiCustomerHotQuestion::getExpireAt, new Date()))
                    .orderByAsc(AiCustomerHotQuestion::getPriority));
        } catch (Exception ignored) {
            rows = List.of();
        }
        List<HotQuestionDefinition> fromDb = rows.stream()
                .map(this::fromEntity)
                .filter(item -> item != null && StringUtils.hasText(item.displayText()))
                .toList();
        return fromDb.isEmpty() ? defaults() : fromDb;
    }

    private HotQuestionDefinition fromEntity(AiCustomerHotQuestion row) {
        if (row == null) {
            return null;
        }
        CustomerServiceIntent intent = CustomerServiceIntent.from(row.getIntentCode());
        JSONObject answer = parseObject(row.getCachedAnswerJson());
        String directAnswer = answer.getString("directAnswer");
        if (!StringUtils.hasText(directAnswer)) {
            directAnswer = answer.getString("answer");
        }
        return new HotQuestionDefinition(
                value(row.getQuestionId()),
                value(row.getDisplayText()),
                StringUtils.hasText(row.getQueryText()) ? row.getQueryText() : row.getDisplayText(),
                intent,
                StringUtils.hasText(row.getRouteHint()) ? row.getRouteHint() : routeHint(intent),
                StringUtils.hasText(row.getAnswerMode()) ? row.getAnswerMode() : RUN_ASSISTANT,
                value(directAnswer),
                readMapList(answer.get("actionButtons")),
                readMapList(row.getSourceRefsJson()),
                readStringList(row.getTagsJson()),
                row.getPriority() == null ? 100 : row.getPriority()
        );
    }

    private HotQuestionDefinition findMatchedDefinition(CustomerQuickAnswerRequest request,
                                                       String message,
                                                       CustomerServiceIntent intent) {
        List<HotQuestionDefinition> definitions = activeDefinitions();
        if (StringUtils.hasText(request.getHotQuestionId())) {
            String questionId = request.getHotQuestionId().trim();
            return definitions.stream()
                    .filter(item -> questionId.equals(item.questionId()))
                    .findFirst()
                    .orElse(null);
        }
        String normalized = message == null ? "" : message.trim();
        if (!StringUtils.hasText(normalized)) {
            return null;
        }
        return definitions.stream()
                .filter(item -> normalized.equals(item.displayText()) || normalized.equals(item.queryText()))
                .findFirst()
                .orElse(null);
    }

    private List<CustomerHotQuestionVo> suggestions(String excludeQuestionId) {
        return activeDefinitions().stream()
                .filter(item -> !item.questionId().equals(excludeQuestionId))
                .sorted(Comparator.comparingInt(HotQuestionDefinition::priority))
                .limit(4)
                .map(this::toVo)
                .toList();
    }

    private CustomerHotQuestionVo toVo(HotQuestionDefinition item) {
        return CustomerHotQuestionVo.builder()
                .questionId(item.questionId())
                .scene(SCENE)
                .displayText(item.displayText())
                .queryText(item.queryText())
                .intentCode(item.intent().name())
                .routeHint(item.routeHint())
                .answerMode(item.answerMode())
                .priority(item.priority())
                .tags(item.tags())
                .build();
    }

    private Map<String, Object> clientContext(CustomerQuickAnswerRequest request,
                                             CustomerServiceIntent intent,
                                             String hotQuestionId,
                                             String routeHint) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (request.getClientContext() != null) {
            context.putAll(request.getClientContext());
        }
        context.put("scene", SCENE);
        context.put("intentHint", intent.name());
        if (StringUtils.hasText(hotQuestionId)) {
            context.put("hotQuestionId", hotQuestionId);
        }
        context.put("routeHint", routeHint);
        putIfHasText(context, "programId", request.getProgramId());
        putIfHasText(context, "orderNo", request.getOrderNo());
        putIfHasText(context, "categoryId", request.getCategoryId());
        return context;
    }

    private Map<String, Object> businessContext(CustomerQuickAnswerRequest request) {
        Map<String, Object> context = new LinkedHashMap<>();
        putIfHasText(context, "programId", request.getProgramId());
        putIfHasText(context, "orderNo", request.getOrderNo());
        putIfHasText(context, "categoryId", request.getCategoryId());
        return context;
    }

    private Map<String, Object> sentimentPayload(SentimentAnalysisService.SentimentResult sentiment) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sentiment", sentiment.sentiment());
        payload.put("intensity", sentiment.intensity());
        payload.put("urgent", sentiment.isUrgent());
        payload.put("emotionTags", sentiment.emotionTags());
        payload.put("shouldEscalate", sentiment.shouldEscalate());
        payload.put("escalationReason", sentiment.escalationReason());
        return payload;
    }

    private List<Map<String, Object>> actionButtons(HotQuestionDefinition item, CustomerWorkItem workItem) {
        List<Map<String, Object>> buttons = new ArrayList<>(item.actionButtons());
        if (workItem != null) {
            buttons.add(Map.of(
                    "label", "查看工单进度",
                    "action", "view_work_item",
                    "workItemId", workItem.getWorkItemId()
            ));
        }
        if (buttons.isEmpty()) {
            buttons.add(Map.of("label", "继续追问", "action", "ask_followup", "intentCode", item.intent().name()));
        }
        return buttons;
    }

    private List<Map<String, Object>> clarificationButtons(CustomerServiceIntent intent) {
        if (!CustomerServiceIntent.GENERAL_CHAT.equals(intent)) {
            return List.of(Map.of(
                    "label", "按这个方向继续",
                    "action", "run_assistant",
                    "intentCode", intent.name()
            ));
        }
        return List.of(
                Map.of("label", "查演出", "action", "ask", "intentCode", CustomerServiceIntent.EVENT_SEARCH.name()),
                Map.of("label", "问退票规则", "action", "ask", "intentCode", CustomerServiceIntent.REFUND_RULE.name()),
                Map.of("label", "咨询订单售后", "action", "ask", "intentCode", CustomerServiceIntent.ORDER_AFTERSALE.name()),
                Map.of("label", "转人工", "action", "create_work_item", "intentCode", CustomerServiceIntent.HUMAN_HANDOFF.name())
        );
    }

    private String withComfortPrefix(String answer, SentimentAnalysisService.SentimentResult sentiment) {
        if (!"NEGATIVE".equals(sentiment.sentiment())) {
            return answer;
        }
        return "先别着急，我会按客服流程帮你把问题推进清楚。\n\n" + answer;
    }

    private Map<String, Object> metricDimensions(CustomerQuickAnswerRequest request,
                                                 String message,
                                                 CustomerServiceIntent intent,
                                                 HotQuestionDefinition matched) {
        Map<String, Object> dimensions = new LinkedHashMap<>();
        dimensions.put("scene", SCENE);
        dimensions.put("question", StringUtils.hasText(message) ? message : value(request.getHotQuestionId()));
        dimensions.put("intentCode", intent.name());
        dimensions.put("hotQuestionId", matched == null ? value(request.getHotQuestionId()) : matched.questionId());
        dimensions.put("answerMode", matched == null ? RUN_ASSISTANT : matched.answerMode());
        return dimensions;
    }

    private String routeHint(CustomerServiceIntent intent) {
        return switch (intent) {
            case EVENT_SEARCH, PROGRAM_DETAIL, TICKET_CATEGORY -> "business";
            case REFUND_RULE, REAL_NAME_RULE, ENTRY_RULE, ORDER_AFTERSALE, INVOICE -> "knowledge";
            case COMPLAINT, HUMAN_HANDOFF -> "human";
            case GENERAL_CHAT -> "general";
        };
    }

    private JSONObject parseObject(String json) {
        if (!StringUtils.hasText(json)) {
            return new JSONObject();
        }
        try {
            return JSON.parseObject(json);
        } catch (Exception ignored) {
            JSONObject object = new JSONObject();
            object.put("directAnswer", json);
            return object;
        }
    }

    private List<Map<String, Object>> readMapList(Object raw) {
        if (raw == null) {
            return List.of();
        }
        try {
            if (raw instanceof List<?> list) {
                return list.stream()
                        .filter(Map.class::isInstance)
                        .map(item -> {
                            Map<String, Object> normalized = new LinkedHashMap<>();
                            ((Map<?, ?>) item).forEach((key, value) -> normalized.put(String.valueOf(key), value));
                            return normalized;
                        })
                        .toList();
            }
            if (raw instanceof String text && StringUtils.hasText(text)) {
                return readMapList(JSON.parseArray(text));
            }
        } catch (Exception ignored) {
        }
        return List.of();
    }

    private List<String> readStringList(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return JSON.parseArray(json, String.class);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private void putIfHasText(Map<String, Object> context, String key, String value) {
        if (StringUtils.hasText(value)) {
            context.put(key, value);
        }
    }

    private String value(String raw) {
        return raw == null ? "" : raw;
    }

    private List<HotQuestionDefinition> defaults() {
        return List.of(
                hot("refund-rule", "退票规则", "节目开演前还能退票吗", CustomerServiceIntent.REFUND_RULE,
                        "退票以项目详情页和订单页展示的规则为准。通常需要先确认项目是否支持退票、申请时间是否在可退窗口内、票品是否已使用或已配送；不确定时建议先查具体演出规则，再决定是否提交售后。",
                        List.of(
                                button("查具体演出", "ask", CustomerServiceIntent.PROGRAM_DETAIL),
                                button("咨询订单售后", "ask", CustomerServiceIntent.ORDER_AFTERSALE),
                                button("转人工", "create_work_item", CustomerServiceIntent.HUMAN_HANDOFF)),
                        refs("平台规则知识库", "退票规则以项目页、订单页和售后政策为准"),
                        List.of("售后", "退票"), 10),
                hot("real-name-entry", "实名入场", "实名入场要带什么证件", CustomerServiceIntent.REAL_NAME_RULE,
                        "实名入场一般需要购票人或观演人携带与订单一致的有效身份证件。若项目要求强实名、不可转赠或人证票一致，请优先按项目详情页的入场说明准备。",
                        List.of(button("查看入场规则", "ask", CustomerServiceIntent.ENTRY_RULE),
                                button("查演出详情", "ask", CustomerServiceIntent.PROGRAM_DETAIL)),
                        refs("平台规则知识库", "实名、证件和入场要求以项目详情页为准"),
                        List.of("实名", "入场"), 20),
                hot("ticket-category", "票档查询", "这个演出还有哪些票档", CustomerServiceIntent.TICKET_CATEGORY,
                        "票档和余票会随销售实时变化。你可以提供演出名称、城市或场次，我会帮你查票档、价格区间和可选场次；涉及下单前会给你确认卡片，不会直接替你下单。",
                        List.of(button("查演出", "ask", CustomerServiceIntent.EVENT_SEARCH),
                                button("查票档", "ask", CustomerServiceIntent.TICKET_CATEGORY)),
                        refs("业务工具", "票档查询通过演出和票档能力读取实时信息"),
                        List.of("售前", "票档"), 30),
                hot("ticket-format", "电子票/纸质票", "电子票和纸质票怎么取", CustomerServiceIntent.ORDER_AFTERSALE,
                        "电子票通常可在订单或票夹中查看，入场时按项目要求出示二维码或身份证件；纸质票请关注配送方式、取票时间和地址。具体以订单页和项目详情页展示为准。",
                        List.of(button("咨询订单售后", "ask", CustomerServiceIntent.ORDER_AFTERSALE),
                                button("查看入场规则", "ask", CustomerServiceIntent.ENTRY_RULE)),
                        refs("平台规则知识库", "票品形式、配送和取票方式以订单页为准"),
                        List.of("电子票", "纸质票"), 40),
                hot("order-aftersale", "订单售后", "订单售后怎么处理", CustomerServiceIntent.ORDER_AFTERSALE,
                        "订单售后需要先确认订单号、演出场次、票品状态和诉求类型。退款、改地址、发票等高风险操作不会自动执行，我会先给你确认路径，必要时创建内部工单转人工处理。",
                        List.of(button("我有订单号", "ask", CustomerServiceIntent.ORDER_AFTERSALE),
                                button("转人工", "create_work_item", CustomerServiceIntent.HUMAN_HANDOFF)),
                        refs("客服流程", "售后问题先收集订单上下文，再进入工单或确认流程"),
                        List.of("售后", "订单"), 50),
                hot("human-handoff", "转人工", "我要转人工客服", CustomerServiceIntent.HUMAN_HANDOFF,
                        "可以，我会为你创建内部客服工单。为了让人工更快接手，请补充订单号、演出名称、场次和你希望解决的问题。",
                        List.of(button("补充订单号", "ask", CustomerServiceIntent.ORDER_AFTERSALE)),
                        refs("客服流程", "转人工工单会携带最近问题、情绪、意图和来源信息"),
                        List.of("转人工"), 60)
        );
    }

    private HotQuestionDefinition hot(String questionId,
                                      String displayText,
                                      String queryText,
                                      CustomerServiceIntent intent,
                                      String directAnswer,
                                      List<Map<String, Object>> actionButtons,
                                      List<Map<String, Object>> sourceRefs,
                                      List<String> tags,
                                      int priority) {
        return new HotQuestionDefinition(questionId, displayText, queryText, intent, routeHint(intent),
                CACHED_ANSWER, directAnswer, actionButtons, sourceRefs, tags, priority);
    }

    private Map<String, Object> button(String label, String action, CustomerServiceIntent intent) {
        return Map.of("label", label, "action", action, "intentCode", intent.name());
    }

    private List<Map<String, Object>> refs(String title, String snippet) {
        return List.of(Map.of("title", title, "source", title, "snippet", snippet));
    }

    private record HotQuestionDefinition(String questionId,
                                         String displayText,
                                         String queryText,
                                         CustomerServiceIntent intent,
                                         String routeHint,
                                         String answerMode,
                                         String directAnswer,
                                         List<Map<String, Object>> actionButtons,
                                         List<Map<String, Object>> sourceRefs,
                                         List<String> tags,
                                         int priority) {
    }
}
