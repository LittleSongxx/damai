package org.javaup.ai.assistant.skill.business;

import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.ai.function.call.OrderCall;
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
    private final OrderCall orderCall;
    private final ProgramQueryService programQueryService;
    private final UserCall userCall;

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

        try {
            assistantRunService.markActionOrdering(action);
            action = assistantRunService.getAction(runId, actionId);
            revalidateSnapshot(snapshot);
            ProgramOrderCreateDto orderCreateDto = snapshot.getProgramOrderCreateDto();
            String orderNumber = orderCall.createOrder(orderCreateDto, action.getIdempotencyKey());

            AssistantActionResultVo resultVo = result(action.getActionId(), AssistantActionStatus.COMPLETED.name(), "订单已创建");
            resultVo.setOrderNumber(orderNumber);
            resultVo.setOrderListAddress(DaMaiConstant.ORDER_LIST_ADDRESS);
            assistantRunService.markActionCompleted(action, resultVo, orderNumber);

            assistantRunService.appendEvent(runId, AssistantEventTypes.TOOL_COMPLETED, Map.of(
                    "runId", runId,
                    "toolName", "createOrder",
                    "toolType", "business",
                    "status", "COMPLETED",
                    "orderNumber", orderNumber
            ));
            assistantRunService.markCompleted(run, "ACTION_APPROVED", "订单已创建");
            assistantRunService.appendEvent(runId, AssistantEventTypes.RUN_COMPLETED, Map.of(
                    "runId", runId,
                    "status", AssistantActionStatus.COMPLETED.name()
            ));
            return resultVo;
        } catch (RuntimeException ex) {
            AssistantActionResultVo failed = result(action.getActionId(), AssistantActionStatus.FAILED.name(), ex.getMessage());
            assistantRunService.markActionFailed(action, "ORDER_CREATE_FAILED", ex.getMessage(), failed);
            failRun(runId, "ACTION_FAILED", ex.getMessage());
            assistantRunService.appendEvent(runId, AssistantEventTypes.TOOL_COMPLETED, Map.of(
                    "runId", runId,
                    "toolName", "createOrder",
                    "toolType", "business",
                    "status", "FAILED",
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
            assistantRunService.markActionExpired(action, expiredResult);
            return expiredResult;
        }
        if (!AssistantActionStatus.WAITING.name().equals(action.getActionStatus())) {
            return result(action.getActionId(), action.getActionStatus(), "该下单请求当前不可拒绝");
        }

        AssistantActionResultVo resultVo = result(action.getActionId(), AssistantActionStatus.REJECTED.name(), "已取消本次下单请求");
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

    private AssistantActionResultVo result(String actionId, String status, String message) {
        AssistantActionResultVo resultVo = new AssistantActionResultVo();
        resultVo.setActionId(actionId);
        resultVo.setStatus(status);
        resultVo.setMessage(message);
        return resultVo;
    }
}
