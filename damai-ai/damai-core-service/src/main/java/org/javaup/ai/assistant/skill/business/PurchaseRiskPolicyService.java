package org.javaup.ai.assistant.skill.business;

import cn.hutool.core.collection.CollectionUtil;
import org.javaup.ai.ai.function.dto.CreateOrderFunctionDto;
import org.javaup.ai.context.AiRequestContextHolder;
import org.javaup.ai.entity.AiAction;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class PurchaseRiskPolicyService {

    private static final Pattern CN_MOBILE = Pattern.compile("^1\\d{10}$");
    private static final int MAX_TICKET_COUNT = 6;

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
