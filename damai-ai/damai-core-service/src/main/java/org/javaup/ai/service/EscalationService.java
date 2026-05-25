package org.javaup.ai.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
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
        // 避免重复升级
        EscalationTicket existing = ticketMapper.selectOne(
                Wrappers.lambdaQuery(EscalationTicket.class)
                        .eq(EscalationTicket::getRunId, runId)
                        .eq(EscalationTicket::getTicketStatus, "OPEN"));
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

    /**
     * 查询待处理工单 - 供人工坐席查看
     */
    public List<EscalationTicket> getPendingTickets() {
        return ticketMapper.selectList(
                Wrappers.lambdaQuery(EscalationTicket.class)
                        .in(EscalationTicket::getTicketStatus, List.of("OPEN", "ASSIGNED", "IN_PROGRESS"))
                        .orderByAsc(EscalationTicket::getPriority)
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
}
