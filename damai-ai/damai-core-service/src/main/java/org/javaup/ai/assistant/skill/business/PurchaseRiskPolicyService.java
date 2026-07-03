package org.javaup.ai.assistant.skill.business;

import cn.hutool.core.collection.CollectionUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.javaup.ai.ai.function.dto.CreateOrderFunctionDto;
import org.javaup.ai.context.AiRequestContextHolder;
import org.javaup.ai.entity.AiAction;
import org.javaup.ai.entity.AiPurchaseReservationAction;
import org.javaup.ai.mapper.AiPurchaseReservationActionMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class PurchaseRiskPolicyService {

    private static final Pattern CN_MOBILE = Pattern.compile("^1\\d{10}$");
    private static final int MAX_TICKET_COUNT = 6;
    private static final Duration APPROVAL_WINDOW = Duration.ofMinutes(5);

    private final AiPurchaseReservationActionMapper reservationActionMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    public PurchaseRiskPolicyService(AiPurchaseReservationActionMapper reservationActionMapper,
                                     @Qualifier("cacheRedisTemplate") RedisTemplate<String, Object> redisTemplate) {
        this.reservationActionMapper = reservationActionMapper;
        this.redisTemplate = redisTemplate;
    }

    public void validatePreviewRequest(CreateOrderFunctionDto request) {
        if (request == null) {
            throw new RuntimeException("购票参数不能为空");
        }
        if (!StringUtils.hasText(request.getMobile()) || !CN_MOBILE.matcher(request.getMobile()).matches()) {
            throw new RuntimeException("手机号格式不正确");
        }
        String currentMobile = AiRequestContextHolder.getRequiredUser().getMobile();
        if (StringUtils.hasText(currentMobile) && !request.getMobile().equals(currentMobile)) {
            throw new RuntimeException("当前登录用户手机号与提交的手机号不一致");
        }
        validateTicketCount(request.getTicketCount(), request.getTicketUserNumberList());
        if (request.getTicketCategoryPrice() == null || request.getTicketCategoryPrice().signum() <= 0) {
            throw new RuntimeException("票档价格不合法");
        }
    }

    public void validateBeforeApproval(PurchaseActionSnapshot snapshot, AiAction action) {
        if (snapshot == null || snapshot.getProgramOrderCreateDto() == null) {
            throw new RuntimeException("下单快照缺失，无法继续审批");
        }
        if (action == null || !StringUtils.hasText(action.getIdempotencyKey())) {
            throw new RuntimeException("下单幂等键缺失，请重新生成购票预览");
        }
        if (snapshot.getExpiresAt() != null && snapshot.getExpiresAt().before(new Date())) {
            throw new RuntimeException("购票快照已过期，请重新生成购票预览");
        }
        if (snapshot.getGeneratedAt() != null && System.currentTimeMillis() - snapshot.getGeneratedAt().getTime() > 30 * 60 * 1000L) {
            throw new RuntimeException("购票快照生成时间过久，请重新生成购票预览");
        }
        if (!StringUtils.hasText(snapshot.getUserMobile()) || !CN_MOBILE.matcher(snapshot.getUserMobile()).matches()) {
            throw new RuntimeException("购票快照手机号格式不正确");
        }
        if (!snapshot.getUserMobile().equals(AiRequestContextHolder.getRequiredUser().getMobile())) {
            throw new RuntimeException("当前登录用户手机号与预览生成时不一致，请重新生成购票预览");
        }
        validateTicketCount(snapshot.getTicketCount(), snapshot.getTicketUserIds());
        if (snapshot.getProgramId() == null || snapshot.getTicketCategoryId() == null || snapshot.getTicketCategoryPrice() == null) {
            throw new RuntimeException("购票快照中的节目或票档信息不完整，请重新生成购票预览");
        }
        validateApprovalFrequency(snapshot, action);
    }

    private void validateApprovalFrequency(PurchaseActionSnapshot snapshot, AiAction action) {
        String currentUserId = String.valueOf(AiRequestContextHolder.getRequiredUser().getUserId());
        if (!StringUtils.hasText(currentUserId)) {
            throw new RuntimeException("无法识别当前登录用户，请重新登录后再审批");
        }

        String actionKey = "damai:ai:purchase:approve:action:" + action.getActionId();
        Boolean firstActionApprove = redisTemplate.opsForValue()
                .setIfAbsent(actionKey, "1", APPROVAL_WINDOW);
        if (Boolean.FALSE.equals(firstActionApprove)) {
            throw new RuntimeException("该购票审批已经提交过，请勿重复点击");
        }

        String userTicketKey = "damai:ai:purchase:approve:user-ticket:" + currentUserId + ":"
                + snapshot.getProgramId() + ":" + snapshot.getTicketCategoryId();
        Boolean firstTicketApprove = redisTemplate.opsForValue()
                .setIfAbsent(userTicketKey, action.getActionId(), APPROVAL_WINDOW);
        if (Boolean.FALSE.equals(firstTicketApprove)) {
            throw new RuntimeException("同一用户同一节目同一票档正在处理其他AI购票请求，请稍后再试");
        }

        Date windowStart = Date.from(Instant.now().minus(APPROVAL_WINDOW));
        Long activeSimilarReservations = reservationActionMapper.selectCount(Wrappers.lambdaQuery(AiPurchaseReservationAction.class)
                .eq(AiPurchaseReservationAction::getUserId, snapshot.getUserId())
                .eq(AiPurchaseReservationAction::getProgramId, snapshot.getProgramId())
                .eq(AiPurchaseReservationAction::getTicketCategoryId, snapshot.getTicketCategoryId())
                .ne(AiPurchaseReservationAction::getActionId, action.getActionId())
                .in(AiPurchaseReservationAction::getReservationStatus, List.of("RESERVED", "CONFIRMED"))
                .ge(AiPurchaseReservationAction::getCreateTime, windowStart)
                .eq(AiPurchaseReservationAction::getStatus, 1));
        if (activeSimilarReservations != null && activeSimilarReservations > 0) {
            throw new RuntimeException("近期已有同节目同票档AI购票请求，请稍后再试或查看订单状态");
        }
    }

    private void validateTicketCount(Integer ticketCount, List<?> ticketUsers) {
        if (ticketCount == null || ticketCount <= 0) {
            throw new RuntimeException("购票数量必须大于0");
        }
        if (ticketCount > MAX_TICKET_COUNT) {
            throw new RuntimeException("单次购票数量不得超过" + MAX_TICKET_COUNT + "张");
        }
        if (CollectionUtil.isEmpty(ticketUsers)) {
            throw new RuntimeException("至少需要选择一名实名购票人");
        }
        if (ticketCount > ticketUsers.size()) {
            throw new RuntimeException("购票数量不得超过实名购票人数");
        }
        Set<?> distinct = new HashSet<>(ticketUsers);
        if (distinct.size() != ticketUsers.size()) {
            throw new RuntimeException("实名购票人不能重复");
        }
    }
}
