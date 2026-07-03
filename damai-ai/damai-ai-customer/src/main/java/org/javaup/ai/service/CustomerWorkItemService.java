package org.javaup.ai.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.dto.CustomerHandoffRequest;
import org.javaup.ai.entity.AiRun;
import org.javaup.ai.entity.CustomerKnowledgeGap;
import org.javaup.ai.entity.CustomerWorkItem;
import org.javaup.ai.entity.CustomerWorkItemEvent;
import org.javaup.ai.mapper.AiRunMapper;
import org.javaup.ai.mapper.CustomerKnowledgeGapMapper;
import org.javaup.ai.mapper.CustomerWorkItemEventMapper;
import org.javaup.ai.mapper.CustomerWorkItemMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CustomerWorkItemService {

    private static final String OPEN = "OPEN";
    private static final String ASSIGNED = "ASSIGNED";
    private static final String IN_PROGRESS = "IN_PROGRESS";
    private static final String RESOLVED = "RESOLVED";
    private static final String CLOSED = "CLOSED";

    private final CustomerWorkItemMapper workItemMapper;
    private final CustomerWorkItemEventMapper eventMapper;
    private final CustomerKnowledgeGapMapper knowledgeGapMapper;
    private final AiRunMapper runMapper;

    @Transactional
    public CustomerWorkItem handoff(CustomerHandoffRequest request, Long userId) {
        String intentCode = value(request.getIntentCode());
        CustomerWorkItem existing = findOpenDuplicate(request.getRunId(), request.getConversationId(), userId, intentCode);
        if (existing != null) {
            return existing;
        }
        Date now = new Date();
        CustomerWorkItem item = new CustomerWorkItem();
        item.setWorkItemId(newId("cwi"));
        item.setRunId(request.getRunId());
        item.setTicketId(item.getWorkItemId());
        item.setUserId(userId);
        item.setSkillGroup(skillGroup(intentCode));
        item.setWorkStatus(OPEN);
        item.setTakeoverStatus("WAITING_HUMAN");
        item.setPriority(priority(request));
        item.setSlaDueAt(new Date(now.getTime() + slaMillis(item.getPriority())));
        item.setExtJson(JSON.toJSONString(extPayload(request, userId)));
        item.setCreateTime(now);
        item.setEditTime(now);
        item.setStatus(1);
        workItemMapper.insert(item);
        appendEvent(item.getWorkItemId(), "CREATED", String.valueOf(userId), Map.of(
                "reason", value(request.getReason()),
                "intentCode", intentCode,
                "priority", item.getPriority()
        ));
        return item;
    }

    @Transactional
    public CustomerWorkItem handoffFromRuntime(String runId,
                                               String conversationId,
                                               Long userId,
                                               String handoffType,
                                               String reason,
                                               String aiDiagnosis) {
        CustomerHandoffRequest request = new CustomerHandoffRequest();
        request.setRunId(runId);
        request.setConversationId(conversationId);
        request.setIntentCode(handoffType);
        request.setReason(reason);
        request.setSuggestedReply(aiDiagnosis);
        request.setUserQuestion(reason);
        request.setBusinessContext(Map.of("source", "assistant_execution_planner"));
        return handoff(request, userId);
    }

    public CustomerWorkItem getUserWorkItem(String workItemId, Long userId) {
        if (!StringUtils.hasText(workItemId) || userId == null) {
            return null;
        }
        return workItemMapper.selectOne(Wrappers.lambdaQuery(CustomerWorkItem.class)
                .eq(CustomerWorkItem::getWorkItemId, workItemId)
                .eq(CustomerWorkItem::getUserId, userId)
                .eq(CustomerWorkItem::getStatus, 1)
                .last("limit 1"));
    }

    public List<CustomerWorkItem> list(String priority, String workStatus, String skillGroup) {
        LambdaQueryWrapper<CustomerWorkItem> query = Wrappers.lambdaQuery(CustomerWorkItem.class)
                .eq(CustomerWorkItem::getStatus, 1);
        if (StringUtils.hasText(priority)) {
            query.eq(CustomerWorkItem::getPriority, priority);
        }
        if (StringUtils.hasText(workStatus)) {
            query.eq(CustomerWorkItem::getWorkStatus, workStatus);
        } else {
            query.in(CustomerWorkItem::getWorkStatus, List.of(OPEN, ASSIGNED, IN_PROGRESS));
        }
        if (StringUtils.hasText(skillGroup)) {
            query.eq(CustomerWorkItem::getSkillGroup, skillGroup);
        }
        return workItemMapper.selectList(query
                .orderByAsc(CustomerWorkItem::getSlaDueAt)
                .orderByDesc(CustomerWorkItem::getCreateTime)
                .last("limit 100"));
    }

    public List<CustomerWorkItem> userWorkItems(Long userId) {
        return workItemMapper.selectList(Wrappers.lambdaQuery(CustomerWorkItem.class)
                .eq(CustomerWorkItem::getUserId, userId)
                .eq(CustomerWorkItem::getStatus, 1)
                .orderByDesc(CustomerWorkItem::getCreateTime)
                .last("limit 50"));
    }

    @Transactional
    public CustomerWorkItem assign(String workItemId, Long agentUserId) {
        CustomerWorkItem item = getRequired(workItemId);
        item.setAssignedTo(String.valueOf(agentUserId));
        item.setWorkStatus(ASSIGNED);
        item.setTakeoverStatus("HUMAN_ASSIGNED");
        item.setEditTime(new Date());
        workItemMapper.updateById(item);
        appendEvent(workItemId, "ASSIGNED", String.valueOf(agentUserId), Map.of("assignedTo", agentUserId));
        return item;
    }

    @Transactional
    public CustomerWorkItem takeover(String workItemId, Long agentUserId) {
        CustomerWorkItem item = getRequired(workItemId);
        item.setAssignedTo(String.valueOf(agentUserId));
        item.setWorkStatus(IN_PROGRESS);
        item.setTakeoverStatus("HUMAN_TAKEOVER");
        item.setEditTime(new Date());
        workItemMapper.updateById(item);
        appendEvent(workItemId, "TAKEOVER", String.valueOf(agentUserId), Map.of("assignedTo", agentUserId));
        return item;
    }

    @Transactional
    public CustomerWorkItem transfer(String workItemId, Long agentUserId, String skillGroup) {
        CustomerWorkItem item = getRequired(workItemId);
        item.setAssignedTo(String.valueOf(agentUserId));
        if (StringUtils.hasText(skillGroup)) {
            item.setSkillGroup(skillGroup);
        }
        item.setWorkStatus(ASSIGNED);
        item.setTakeoverStatus("TRANSFERRED");
        item.setEditTime(new Date());
        workItemMapper.updateById(item);
        appendEvent(workItemId, "TRANSFERRED", String.valueOf(agentUserId),
                Map.of("assignedTo", agentUserId, "skillGroup", value(skillGroup)));
        return item;
    }

    @Transactional
    public CustomerWorkItem resolve(String workItemId, String conclusion, Long resolvedBy) {
        CustomerWorkItem item = getRequired(workItemId);
        item.setWorkStatus(RESOLVED);
        item.setConclusion(conclusion);
        item.setTakeoverStatus("HUMAN_RESOLVED");
        item.setEditTime(new Date());
        workItemMapper.updateById(item);
        appendEvent(workItemId, "RESOLVED", String.valueOf(resolvedBy), Map.of("conclusion", value(conclusion)));
        createKnowledgeGapIfNeeded(item, conclusion, resolvedBy);
        return item;
    }

    @Transactional
    public CustomerWorkItem close(String workItemId, Long closedBy) {
        CustomerWorkItem item = getRequired(workItemId);
        item.setWorkStatus(CLOSED);
        item.setEditTime(new Date());
        workItemMapper.updateById(item);
        appendEvent(workItemId, "CLOSED", String.valueOf(closedBy), Map.of());
        return item;
    }

    @Transactional
    public CustomerWorkItem satisfaction(String workItemId, Integer score, Long userId) {
        CustomerWorkItem item = getUserWorkItem(workItemId, userId);
        if (item == null) {
            throw new IllegalArgumentException("work item not found: " + workItemId);
        }
        item.setSatisfactionScore(score);
        item.setEditTime(new Date());
        workItemMapper.updateById(item);
        appendEvent(workItemId, "SATISFACTION", String.valueOf(userId), Map.of("score", score == null ? 0 : score));
        return item;
    }

    public List<Map<String, Object>> unresolvedCases() {
        return list(null, null, null).stream().map(item -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("workItemId", item.getWorkItemId());
            row.put("priority", item.getPriority());
            row.put("skillGroup", item.getSkillGroup());
            row.put("workStatus", item.getWorkStatus());
            row.put("takeoverStatus", item.getTakeoverStatus());
            row.put("slaDueAt", item.getSlaDueAt());
            row.put("assignedTo", item.getAssignedTo());
            row.put("context", item.getExtJson());
            row.put("createTime", item.getCreateTime());
            return row;
        }).toList();
    }

    private CustomerWorkItem getRequired(String workItemId) {
        CustomerWorkItem item = workItemMapper.selectOne(Wrappers.lambdaQuery(CustomerWorkItem.class)
                .eq(CustomerWorkItem::getWorkItemId, workItemId)
                .eq(CustomerWorkItem::getStatus, 1)
                .last("limit 1"));
        if (item == null) {
            throw new IllegalArgumentException("work item not found: " + workItemId);
        }
        return item;
    }

    private CustomerWorkItem findOpenDuplicate(String runId, String conversationId, Long userId, String intentCode) {
        LambdaQueryWrapper<CustomerWorkItem> query = Wrappers.lambdaQuery(CustomerWorkItem.class)
                .in(CustomerWorkItem::getWorkStatus, List.of(OPEN, ASSIGNED, IN_PROGRESS))
                .eq(CustomerWorkItem::getStatus, 1);
        if (StringUtils.hasText(runId)) {
            query.eq(CustomerWorkItem::getRunId, runId);
        } else {
            if (!StringUtils.hasText(conversationId) || userId == null) {
                return null;
            }
            query.eq(CustomerWorkItem::getUserId, userId)
                    .like(CustomerWorkItem::getExtJson, conversationId)
                    .like(CustomerWorkItem::getExtJson, intentCode);
        }
        return workItemMapper.selectOne(query.last("limit 1"));
    }

    private void createKnowledgeGapIfNeeded(CustomerWorkItem item, String conclusion, Long operatorId) {
        if (!StringUtils.hasText(conclusion)) {
            return;
        }
        CustomerKnowledgeGap gap = new CustomerKnowledgeGap();
        gap.setGapId(newId("gap"));
        gap.setSourceType("WORK_ITEM");
        gap.setSourceId(item.getWorkItemId());
        gap.setConversationId(readExt(item, "conversationId"));
        gap.setUserId(item.getUserId());
        gap.setIntentCode(readExt(item, "intentCode"));
        gap.setIssueCategory(item.getSkillGroup());
        gap.setQuestion(readExt(item, "userQuestion"));
        gap.setEvidenceJson(JSON.toJSONString(Map.of(
                "workItemId", item.getWorkItemId(),
                "conclusion", conclusion,
                "context", item.getExtJson() == null ? "" : item.getExtJson()
        )));
        gap.setGapStatus("PENDING_REVIEW");
        gap.setOperatorId(String.valueOf(operatorId));
        gap.setCreateTime(new Date());
        gap.setEditTime(new Date());
        gap.setStatus(1);
        knowledgeGapMapper.insert(gap);
    }

    private String readExt(CustomerWorkItem item, String key) {
        try {
            Map<?, ?> map = JSON.parseObject(item.getExtJson(), Map.class);
            Object value = map == null ? null : map.get(key);
            return value == null ? "" : String.valueOf(value);
        } catch (Exception ignored) {
            return "";
        }
    }

    private Map<String, Object> extPayload(CustomerHandoffRequest request, Long userId) {
        Map<String, Object> ext = new LinkedHashMap<>();
        ext.put("conversationId", value(request.getConversationId()));
        ext.put("userQuestion", value(request.getUserQuestion()));
        ext.put("aiAnswer", value(request.getAiAnswer()));
        ext.put("sentiment", value(request.getSentiment()));
        ext.put("sentimentIntensity", request.getSentimentIntensity() == null ? 0D : request.getSentimentIntensity());
        ext.put("intentCode", value(request.getIntentCode()));
        ext.put("sourceRefs", request.getSourceRefs() == null ? List.of() : request.getSourceRefs());
        ext.put("businessContext", request.getBusinessContext() == null ? Map.of() : request.getBusinessContext());
        ext.put("reason", value(request.getReason()));
        ext.put("suggestedReply", StringUtils.hasText(request.getSuggestedReply())
                ? request.getSuggestedReply()
                : "先确认用户诉求和订单/场次信息，再按平台规则给出明确下一步。");
        ext.put("dialogueSummary", buildDialogueSummary(request.getConversationId(), userId));
        return ext;
    }

    private void appendEvent(String workItemId, String eventType, String operatorId, Map<String, Object> payload) {
        CustomerWorkItemEvent event = new CustomerWorkItemEvent();
        event.setEventId(newId("cwe"));
        event.setWorkItemId(workItemId);
        event.setEventType(eventType);
        event.setOperatorId(operatorId);
        event.setEventPayloadJson(JSON.toJSONString(payload == null ? Map.of() : payload));
        event.setCreateTime(new Date());
        event.setEditTime(new Date());
        event.setStatus(1);
        eventMapper.insert(event);
    }

    private String buildDialogueSummary(String conversationId, Long userId) {
        if (!StringUtils.hasText(conversationId) || userId == null) {
            return "";
        }
        try {
            List<AiRun> runs = runMapper.selectList(Wrappers.lambdaQuery(AiRun.class)
                    .eq(AiRun::getConversationId, conversationId)
                    .eq(AiRun::getUserId, userId)
                    .orderByAsc(AiRun::getCreateTime)
                    .last("limit 10"));
            StringBuilder builder = new StringBuilder();
            for (AiRun run : runs) {
                builder.append("用户: ").append(value(run.getUserMessage())).append("\n");
                builder.append("助手: ").append(value(run.getResponseSummary())).append("\n---\n");
            }
            return builder.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private String priority(CustomerHandoffRequest request) {
        if (request.getSentimentIntensity() != null && request.getSentimentIntensity() >= 0.85D) {
            return "CRITICAL";
        }
        if ("COMPLAINT".equals(request.getIntentCode()) || "NEGATIVE".equals(request.getSentiment())) {
            return "HIGH";
        }
        if ("HUMAN_HANDOFF".equals(request.getIntentCode()) || "ORDER_AFTERSALE".equals(request.getIntentCode())) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private String skillGroup(String intentCode) {
        if ("COMPLAINT".equals(intentCode)) {
            return "complaint";
        }
        if ("ORDER_AFTERSALE".equals(intentCode) || "REFUND_RULE".equals(intentCode)) {
            return "aftersale";
        }
        if ("TICKET_CATEGORY".equals(intentCode) || "EVENT_SEARCH".equals(intentCode)) {
            return "ticketing";
        }
        return "general";
    }

    private long slaMillis(String priority) {
        return switch (priority) {
            case "CRITICAL" -> 30 * 60 * 1000L;
            case "HIGH" -> 2 * 60 * 60 * 1000L;
            case "MEDIUM" -> 8 * 60 * 60 * 1000L;
            default -> 24 * 60 * 60 * 1000L;
        };
    }

    private String newId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }

    private String value(String raw) {
        return raw == null ? "" : raw;
    }
}
