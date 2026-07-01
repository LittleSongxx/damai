package org.javaup.ai.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.dto.CustomerEscalationRequest;
import org.javaup.ai.entity.AiRun;
import org.javaup.ai.entity.EscalationTicket;
import org.javaup.ai.mapper.AiRunMapper;
import org.javaup.ai.mapper.EscalationTicketMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 智能升级服务 - 参考 Intercom/Zendesk 的 escalation workflow。
 * 处理从AI到人工客服的升级流程，携带完整上下文。
 */
@Slf4j
@Service
public class EscalationService {

    private final EscalationTicketMapper ticketMapper;
    private final AiRunMapper runMapper;

    public EscalationService(EscalationTicketMapper ticketMapper, AiRunMapper runMapper) {
        this.ticketMapper = ticketMapper;
        this.runMapper = runMapper;
    }

    /**
     * 创建升级工单 - emotional/unsolved/complex 等场景
     */
    @Transactional
    public EscalationTicket escalate(String runId, String conversationId, Long userId,
                                      String escalationType, String reason, String aiDiagnosis) {
        EscalationTicket existing = findOpenDuplicate(runId, conversationId, userId, escalationType);
        if (existing != null) {
            log.info("Escalation already exists for runId={}", runId);
            return existing;
        }

        String dialogueSummary = buildDialogueSummary(conversationId, userId);

        EscalationTicket ticket = new EscalationTicket();
        ticket.setTicketId(UUID.randomUUID().toString().replace("-", ""));
        ticket.setRunId(runId);
        ticket.setConversationId(conversationId);
        ticket.setUserId(userId);
        ticket.setEscalationType(escalationType);
        ticket.setPriority(determinePriority(escalationType, reason));
        ticket.setTicketStatus("OPEN");
        ticket.setAiDiagnosis(aiDiagnosis);
        ticket.setDialogueSummary(dialogueSummary);
        ticket.setCreateTime(new Date());
        ticket.setEditTime(new Date());
        ticket.setStatus(1);
        ticketMapper.insert(ticket);

        log.info("Escalation ticket created: ticketId={}, type={}, priority={}",
                ticket.getTicketId(), escalationType, ticket.getPriority());

        return ticket;
    }

    @Transactional
    public EscalationTicket createCustomerEscalation(CustomerEscalationRequest request, Long userId) {
        String escalationType = "HUMAN_HANDOFF".equals(request.getIntentCode()) ? "HUMAN_HANDOFF" : "CUSTOMER_SERVICE";
        EscalationTicket existing = findOpenDuplicate(request.getRunId(), request.getConversationId(), userId, escalationType);
        if (existing != null) {
            return existing;
        }
        String dialogueSummary = StringUtils.hasText(request.getUserQuestion())
                ? "用户: " + request.getUserQuestion() + "\n助手: " + value(request.getAiAnswer())
                : buildDialogueSummary(request.getConversationId(), userId);
        EscalationTicket ticket = new EscalationTicket();
        ticket.setTicketId(UUID.randomUUID().toString().replace("-", ""));
        ticket.setRunId(request.getRunId());
        ticket.setConversationId(request.getConversationId());
        ticket.setUserId(userId);
        ticket.setEscalationType(escalationType);
        ticket.setPriority(customerPriority(request));
        ticket.setTicketStatus("OPEN");
        ticket.setAiDiagnosis(StringUtils.hasText(request.getReason()) ? request.getReason() : "用户请求智能客服转人工");
        ticket.setDialogueSummary(dialogueSummary);
        ticket.setContextJson(JSON.toJSONString(Map.of(
                "userQuestion", value(request.getUserQuestion()),
                "aiAnswer", value(request.getAiAnswer()),
                "sentimentIntensity", request.getSentimentIntensity() == null ? 0D : request.getSentimentIntensity(),
                "sourceRefs", request.getSourceRefs() == null ? List.of() : request.getSourceRefs(),
                "businessContext", request.getBusinessContext() == null ? Map.of() : request.getBusinessContext()
        )));
        ticket.setSentiment(request.getSentiment());
        ticket.setIntentCode(request.getIntentCode());
        ticket.setSuggestedReply(StringUtils.hasText(request.getSuggestedReply())
                ? request.getSuggestedReply()
                : "先确认用户诉求和订单/场次信息，再按退票、实名、入场或售后规则给出明确下一步。");
        ticket.setCreateTime(new Date());
        ticket.setEditTime(new Date());
        ticket.setStatus(1);
        ticketMapper.insert(ticket);
        log.info("Customer service escalation created: ticketId={}, priority={}", ticket.getTicketId(), ticket.getPriority());
        return ticket;
    }

    public EscalationTicket getUserTicket(String ticketId, Long userId) {
        if (!StringUtils.hasText(ticketId) || userId == null) {
            return null;
        }
        return ticketMapper.selectOne(Wrappers.lambdaQuery(EscalationTicket.class)
                .eq(EscalationTicket::getTicketId, ticketId)
                .eq(EscalationTicket::getUserId, userId)
                .eq(EscalationTicket::getStatus, 1)
                .last("limit 1"));
    }

    /**
     * 查询待处理工单 - 供人工坐席查看
     */
    public List<EscalationTicket> getPendingTickets() {
        return getPendingTickets(null, null, null);
    }

    public List<EscalationTicket> getPendingTickets(String priority, String sentiment, String intentCode) {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<EscalationTicket> query =
                Wrappers.lambdaQuery(EscalationTicket.class)
                        .in(EscalationTicket::getTicketStatus, List.of("OPEN", "ASSIGNED", "IN_PROGRESS"))
                        .eq(EscalationTicket::getStatus, 1);
        if (StringUtils.hasText(priority)) {
            query.eq(EscalationTicket::getPriority, priority);
        }
        if (StringUtils.hasText(sentiment)) {
            query.eq(EscalationTicket::getSentiment, sentiment);
        }
        if (StringUtils.hasText(intentCode)) {
            query.eq(EscalationTicket::getIntentCode, intentCode);
        }
        return ticketMapper.selectList(
                query.orderByAsc(EscalationTicket::getPriority)
                        .orderByAsc(EscalationTicket::getCreateTime));
    }

    /**
     * 分配工单给客服
     */
    @Transactional
    public EscalationTicket assignTicket(String ticketId, Long agentUserId) {
        EscalationTicket ticket = ticketMapper.selectOne(
                Wrappers.lambdaQuery(EscalationTicket.class)
                        .eq(EscalationTicket::getTicketId, ticketId));
        if (ticket == null) {
            throw new IllegalArgumentException("Ticket not found: " + ticketId);
        }
        ticket.setAssignedTo(agentUserId);
        ticket.setTicketStatus("ASSIGNED");
        ticket.setEditTime(new Date());
        ticketMapper.updateById(ticket);
        return ticket;
    }

    /**
     * 解决工单
     */
    @Transactional
    public EscalationTicket resolveTicket(String ticketId, String resolution, Long resolvedBy) {
        EscalationTicket ticket = ticketMapper.selectOne(
                Wrappers.lambdaQuery(EscalationTicket.class)
                        .eq(EscalationTicket::getTicketId, ticketId));
        if (ticket == null) {
            throw new IllegalArgumentException("Ticket not found: " + ticketId);
        }
        ticket.setTicketStatus("RESOLVED");
        ticket.setResolution(resolution);
        ticket.setResolvedBy(resolvedBy);
        ticket.setResolvedAt(new Date());
        ticket.setEditTime(new Date());
        ticketMapper.updateById(ticket);
        return ticket;
    }

    /**
     * 获取用户的升级历史
     */
    public List<EscalationTicket> getUserTickets(Long userId) {
        return ticketMapper.selectList(
                Wrappers.lambdaQuery(EscalationTicket.class)
                        .eq(EscalationTicket::getUserId, userId)
                        .orderByDesc(EscalationTicket::getCreateTime));
    }

    private String buildDialogueSummary(String conversationId, Long userId) {
        try {
            List<AiRun> runs = runMapper.selectList(
                    Wrappers.lambdaQuery(AiRun.class)
                            .eq(AiRun::getConversationId, conversationId)
                            .eq(AiRun::getUserId, userId)
                            .orderByAsc(AiRun::getCreateTime)
                            .last("limit 10"));

            if (runs.isEmpty()) return "";

            StringBuilder sb = new StringBuilder();
            for (AiRun run : runs) {
                sb.append("用户: ").append(StringUtils.hasText(run.getUserMessage()) ? run.getUserMessage() : "").append("\n");
                sb.append("助手: ").append(StringUtils.hasText(run.getResponseSummary()) ? run.getResponseSummary() : "").append("\n");
                sb.append("---\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String determinePriority(String escalationType, String reason) {
        if ("SENTIMENT".equals(escalationType)) {
            return reason != null && reason.contains("紧急") ? "CRITICAL" : "HIGH";
        }
        if ("UNRESOLVED".equals(escalationType)) return "MEDIUM";
        if ("COMPLEX".equals(escalationType)) return "MEDIUM";
        return "LOW";
    }

    private EscalationTicket findOpenDuplicate(String runId, String conversationId, Long userId, String escalationType) {
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<EscalationTicket> query =
                Wrappers.lambdaQuery(EscalationTicket.class)
                        .eq(EscalationTicket::getTicketStatus, "OPEN")
                        .eq(EscalationTicket::getStatus, 1);
        if (StringUtils.hasText(runId)) {
            query.eq(EscalationTicket::getRunId, runId);
        } else {
            if (!StringUtils.hasText(conversationId) || userId == null) {
                return null;
            }
            query.eq(EscalationTicket::getConversationId, conversationId)
                    .eq(EscalationTicket::getUserId, userId)
                    .eq(EscalationTicket::getEscalationType, escalationType);
        }
        return ticketMapper.selectOne(query.last("limit 1"));
    }

    private String customerPriority(CustomerEscalationRequest request) {
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

    private String value(String raw) {
        return raw == null ? "" : raw;
    }
}
