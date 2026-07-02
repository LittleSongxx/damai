package org.javaup.ai.assistant.skill.business;

import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.ai.function.call.UserCall;
import org.javaup.ai.assistant.AssistantEventTypes;
import org.javaup.ai.assistant.AssistantActionStatus;
import org.javaup.ai.assistant.AssistantRunService;
import org.javaup.ai.constants.DaMaiConstant;
import org.javaup.ai.context.AiRequestContextHolder;
import org.javaup.ai.dto.ProgramOrderCreateDto;
import org.javaup.ai.entity.AiAction;
import org.javaup.ai.entity.AiRun;
import org.javaup.ai.vo.AssistantActionResultVo;
import org.javaup.ai.vo.ProgramDetailVo;
import org.javaup.ai.vo.TicketCategoryVo;
import org.javaup.ai.vo.TicketUserVo;
import org.javaup.ai.service.PurchaseReservationAuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PurchaseActionService {

    private final AssistantRunService assistantRunService;
    private final ProgramQueryService programQueryService;
    private final UserCall userCall;
    private final TicketReservationGateway ticketReservationGateway;
    private final PurchaseRiskPolicyService riskPolicyService;
    private final PurchaseReservationAuditService reservationAuditService;

    @Transactional(rollbackFor = Exception.class)
    public AssistantActionResultVo approve(String runId, String actionId) {
        AiAction action = assistantRunService.getAction(runId, actionId);
        if (action == null) {
            return result(actionId, AssistantActionStatus.FAILED.name(), "没有找到可审批的下单动作");
        }
        if (AssistantActionStatus.COMPLETED.name().equals(action.getActionStatus())) {
            return parseStoredResult(action, "订单已创建");
        }
        if (AssistantActionStatus.REJECTED.name().equals(action.getActionStatus())) {
            return parseStoredResult(action, "该下单请求已经被拒绝");
        }
        if (AssistantActionStatus.EXPIRED.name().equals(action.getActionStatus())) {
            return parseStoredResult(action, "该下单请求已经过期，请重新生成购票预览");
        }
        if (AssistantActionStatus.APPROVING.name().equals(action.getActionStatus())
                || AssistantActionStatus.ORDERING.name().equals(action.getActionStatus())) {
            return result(action.getActionId(), action.getActionStatus(), "该下单请求正在处理中，请稍后查看结果");
        }
        if (!AssistantActionStatus.WAITING.name().equals(action.getActionStatus())) {
            return result(action.getActionId(), action.getActionStatus(), "该下单请求当前不可审批");
        }
        if (isExpired(action)) {
            AssistantActionResultVo expiredResult = result(action.getActionId(), AssistantActionStatus.EXPIRED.name(), "该下单请求已经过期，请重新生成购票预览");
            assistantRunService.markActionExpired(action, expiredResult);
            failRun(runId, "ACTION_EXPIRED", expiredResult.getMessage());
            return expiredResult;
        }

        Date now = new Date();
        if (!assistantRunService.claimActionForApproval(runId, actionId, now)) {
            AiAction refreshed = assistantRunService.getAction(runId, actionId);
            return refreshed == null
                    ? result(actionId, AssistantActionStatus.FAILED.name(), "没有找到可审批的下单动作")
                    : statusAwareResult(refreshed);
        }

        action = assistantRunService.getAction(runId, actionId);
        assistantRunService.appendEvent(runId, AssistantEventTypes.ACTION_PROCESSING, Map.of(
                "runId", runId,
                "actionId", actionId,
                "status", AssistantActionStatus.APPROVING.name()
        ));
        PurchaseActionSnapshot snapshot = parseSnapshot(action);
        AiRun run = assistantRunService.getRunInternal(runId);
        TicketReservation reservation = null;

        try {
            assistantRunService.markActionOrdering(action);
            action = assistantRunService.getAction(runId, actionId);
            riskPolicyService.validateBeforeApproval(snapshot, action);
            revalidateSnapshot(snapshot);
            String reservationIdempotencyKey = action.getIdempotencyKey() + ":reservation";
            reservation = ticketReservationGateway.reserve(snapshot, reservationIdempotencyKey);
            if (reservation == null || !reservation.locked() || reservation.reservationId() == null) {
                throw new RuntimeException("库存预留失败，请稍后重试");
            }
            reservationAuditService.reserved(runId, actionId, snapshot, reservation, reservationIdempotencyKey);
            String orderIdempotencyKey = action.getIdempotencyKey() + ":reservation:" + reservation.reservationId();
            String orderNumber = ticketReservationGateway.confirm(snapshot, reservation, orderIdempotencyKey);
            reservationAuditService.confirmed(reservation.reservationId(), orderNumber);

            AssistantActionResultVo resultVo = result(action.getActionId(), AssistantActionStatus.COMPLETED.name(), "订单已创建");
            resultVo.setOrderNumber(orderNumber);
            resultVo.setOrderListAddress(DaMaiConstant.ORDER_LIST_ADDRESS);
            assistantRunService.markActionCompleted(action, resultVo, orderNumber);

            assistantRunService.appendEvent(runId, AssistantEventTypes.TOOL_COMPLETED, Map.of(
                    "runId", runId,
                    "toolName", "createOrder",
                    "toolType", "business",
                    "status", "COMPLETED",
                    "reservationId", reservation.reservationId(),
                    "orderNumber", orderNumber
            ));
            assistantRunService.markCompleted(run, "ACTION_APPROVED", "订单已创建");
            assistantRunService.appendEvent(runId, AssistantEventTypes.RUN_COMPLETED, Map.of(
                    "runId", runId,
                    "status", AssistantActionStatus.COMPLETED.name()
            ));
            return resultVo;
        } catch (RuntimeException ex) {
            releaseReservation(reservation, "ORDER_CREATE_FAILED:" + ex.getMessage());
            if (reservation != null) {
                reservationAuditService.failed(reservation.reservationId(), ex.getMessage());
            }
            AssistantActionResultVo failed = result(action.getActionId(), AssistantActionStatus.FAILED.name(), ex.getMessage());
            assistantRunService.markActionFailed(action, "ORDER_CREATE_FAILED", ex.getMessage(), failed);
            failRun(runId, "ACTION_FAILED", ex.getMessage());
            assistantRunService.appendEvent(runId, AssistantEventTypes.TOOL_COMPLETED, Map.of(
                    "runId", runId,
                    "toolName", "createOrder",
                    "toolType", "business",
                    "status", "FAILED",
                    "reservationId", reservation == null ? "" : reservation.reservationId(),
                    "message", ex.getMessage()
            ));
            return failed;
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public AssistantActionResultVo reject(String runId, String actionId) {
        AiAction action = assistantRunService.getAction(runId, actionId);
        if (action == null) {
            return result(actionId, AssistantActionStatus.FAILED.name(), "没有找到可拒绝的下单动作");
        }
        if (AssistantActionStatus.REJECTED.name().equals(action.getActionStatus())) {
            return parseStoredResult(action, "已取消本次下单请求");
        }
        if (AssistantActionStatus.COMPLETED.name().equals(action.getActionStatus())) {
            return parseStoredResult(action, "订单已创建，当前无法再拒绝该动作");
        }
        if (AssistantActionStatus.APPROVING.name().equals(action.getActionStatus())
                || AssistantActionStatus.ORDERING.name().equals(action.getActionStatus())) {
            return result(action.getActionId(), action.getActionStatus(), "该下单请求正在处理中，当前无法拒绝");
        }
        if (isExpired(action)) {
            AssistantActionResultVo expiredResult = result(action.getActionId(), AssistantActionStatus.EXPIRED.name(), "该下单请求已经过期");
            releaseReservation(parseSnapshot(action), "ACTION_EXPIRED");
            assistantRunService.markActionExpired(action, expiredResult);
            return expiredResult;
        }
        if (!AssistantActionStatus.WAITING.name().equals(action.getActionStatus())) {
            return result(action.getActionId(), action.getActionStatus(), "该下单请求当前不可拒绝");
        }

        AssistantActionResultVo resultVo = result(action.getActionId(), AssistantActionStatus.REJECTED.name(), "已取消本次下单请求");
        // Dify 风格智能重新提问: 拒绝后提供替代方案，而非仅告知"已取消"
        resultVo.setSuggestedAlternatives(buildRejectionAlternatives(action));
        releaseReservation(parseSnapshot(action), "ACTION_REJECTED");
        assistantRunService.markActionRejected(action, resultVo);
        AiRun run = assistantRunService.getRunInternal(runId);
        assistantRunService.markCompleted(run, "ACTION_REJECTED", "用户取消下单");
        assistantRunService.appendEvent(runId, AssistantEventTypes.RUN_COMPLETED, Map.of(
                "runId", runId,
                "status", AssistantActionStatus.REJECTED.name()
        ));
        return resultVo;
    }

    private void revalidateSnapshot(PurchaseActionSnapshot snapshot) {
        if (snapshot == null || snapshot.getProgramOrderCreateDto() == null) {
            throw new RuntimeException("下单快照缺失，无法继续审批");
        }
        if (snapshot.getUserMobile() != null
                && !snapshot.getUserMobile().equals(AiRequestContextHolder.getRequiredUser().getMobile())) {
            throw new RuntimeException("当前登录用户手机号与预览生成时不一致，请重新生成购票预览");
        }
        List<TicketUserVo> ticketUsers = userCall.ticketUserList(AiRequestContextHolder.getRequiredUser().getUserId());
        for (Long ticketUserId : snapshot.getTicketUserIds()) {
            boolean matched = ticketUsers.stream().anyMatch(ticketUserVo -> ticketUserId.equals(ticketUserVo.getId()));
            if (!matched) {
                throw new RuntimeException("购票人信息已变化，请重新生成购票预览");
            }
        }
        ProgramDetailVo latestProgram = programQueryService.getProgramDetailRawById(snapshot.getProgramId());
        if (latestProgram == null) {
            throw new RuntimeException("节目已不可购买，请重新查询");
        }
        TicketCategoryVo ticketCategoryVo = latestProgram.getTicketCategoryVoList().stream()
                .filter(item -> snapshot.getTicketCategoryId().equals(item.getId()))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("票档已不存在，请重新生成购票预览"));
        if (ticketCategoryVo.getPrice() == null || snapshot.getTicketCategoryPrice() == null) {
            throw new RuntimeException("票档价格信息不完整，请重新生成购票预览");
        }
        if (ticketCategoryVo.getPrice().compareTo(snapshot.getTicketCategoryPrice()) != 0) {
            throw new RuntimeException("票档价格已变化，请重新生成购票预览");
        }
        Long remainNumber = ticketCategoryVo.getRemainNumber();
        if (remainNumber != null && remainNumber < snapshot.getTicketCount()) {
            throw new RuntimeException("当前余票不足，请重新生成购票预览");
        }
    }

    private void releaseReservation(PurchaseActionSnapshot snapshot, String reason) {
        if (snapshot == null || snapshot.getReservationId() == null || snapshot.getReservationId().isBlank()) {
            return;
        }
        ticketReservationGateway.release(snapshot.getReservationId(), reason);
    }

    private void releaseReservation(TicketReservation reservation, String reason) {
        if (reservation == null || reservation.reservationId() == null || reservation.reservationId().isBlank()) {
            return;
        }
        ticketReservationGateway.release(reservation.reservationId(), reason);
        reservationAuditService.released(reservation.reservationId(), reason);
    }

    private AssistantActionResultVo statusAwareResult(AiAction action) {
        return switch (AssistantActionStatus.valueOf(action.getActionStatus())) {
            case COMPLETED -> parseStoredResult(action, "订单已创建");
            case REJECTED -> parseStoredResult(action, "该下单请求已经被拒绝");
            case EXPIRED -> parseStoredResult(action, "该下单请求已经过期，请重新生成购票预览");
            case APPROVING, ORDERING -> result(action.getActionId(), action.getActionStatus(), "该下单请求正在处理中，请稍后查看结果");
            case FAILED -> parseStoredResult(action, action.getFailureMessage() == null ? "下单失败，请重新发起" : action.getFailureMessage());
            default -> result(action.getActionId(), action.getActionStatus(), "该下单请求当前不可审批");
        };
    }

    private AssistantActionResultVo parseStoredResult(AiAction action, String fallbackMessage) {
        if (action.getResultJson() != null && !action.getResultJson().isBlank()) {
            AssistantActionResultVo parsed = JSON.parseObject(action.getResultJson(), AssistantActionResultVo.class);
            if (parsed != null) {
                if (parsed.getActionId() == null) {
                    parsed.setActionId(action.getActionId());
                }
                if (parsed.getStatus() == null) {
                    parsed.setStatus(action.getActionStatus());
                }
                if (parsed.getMessage() == null) {
                    parsed.setMessage(fallbackMessage);
                }
                return parsed;
            }
        }
        AssistantActionResultVo resultVo = result(action.getActionId(), action.getActionStatus(), fallbackMessage);
        resultVo.setOrderNumber(action.getOrderNumber());
        if (action.getOrderNumber() != null) {
            resultVo.setOrderListAddress(DaMaiConstant.ORDER_LIST_ADDRESS);
        }
        return resultVo;
    }

    private PurchaseActionSnapshot parseSnapshot(AiAction action) {
        return JSON.parseObject(action.getPreviewJson(), PurchaseActionSnapshot.class);
    }

    private void failRun(String runId, String stage, String message) {
        AiRun run = assistantRunService.getRunInternal(runId);
        if (run == null) {
            return;
        }
        assistantRunService.markFailed(run, stage, message);
        assistantRunService.appendEvent(runId, AssistantEventTypes.RUN_FAILED, Map.of(
                "runId", runId,
                "message", message
        ));
    }

    private boolean isExpired(AiAction action) {
        return action.getExpiresAt() != null && action.getExpiresAt().before(new Date());
    }

    /**
     * 构建拒绝后的替代方案推荐 —— 遵循 Dify 智能重新提问设计。
     *
     * <p>基于预览快照中的节目、城市、艺人、票档等信息，生成可操作的下一步建议。
     * 每条建议为面向用户的自然语言，LLM/前端可直接展示。
     */
    private List<String> buildRejectionAlternatives(AiAction action) {
        PurchaseActionSnapshot snapshot = parseSnapshot(action);
        if (snapshot == null) {
            return List.of();
        }
        List<String> alternatives = new java.util.ArrayList<>();
        String city = snapshot.getCityName();
        String actor = snapshot.getActor();
        String programTitle = snapshot.getProgramTitle();

        if (programTitle != null && !programTitle.isBlank()) {
            alternatives.add("查看「" + programTitle + "」的其他票档和时间，可能有更合适的场次或价位");
        }
        if (city != null && !city.isBlank()) {
            alternatives.add("搜索「" + city + "」其他同期演出，看看有没有更感兴趣的节目");
        }
        if (actor != null && !actor.isBlank()) {
            alternatives.add("搜索「" + actor + "」的其他演出场次或巡演城市");
        }
        alternatives.add("调整筛选条件（如扩大城市范围、放宽价格区间）后重新搜索");
        alternatives.add("如果没有急需购票，可关注大麦APP的最新上架信息");
        return alternatives;
    }

    private AssistantActionResultVo result(String actionId, String status, String message) {
        AssistantActionResultVo resultVo = new AssistantActionResultVo();
        resultVo.setActionId(actionId);
        resultVo.setStatus(status);
        resultVo.setMessage(message);
        return resultVo;
    }
}
