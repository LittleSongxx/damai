package org.javaup.ai.assistant.skill.business;

import com.alibaba.fastjson.JSON;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.ai.function.call.OrderCall;
import org.javaup.ai.assistant.AssistantEventTypes;
import org.javaup.ai.assistant.AssistantActionStatus;
import org.javaup.ai.assistant.AssistantRunService;
import org.javaup.ai.constants.DaMaiConstant;
import org.javaup.ai.dto.ProgramOrderCreateDto;
import org.javaup.ai.entity.AiAction;
import org.javaup.ai.entity.AiRun;
import org.javaup.ai.vo.AssistantActionResultVo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class PurchaseActionService {

    private final AssistantRunService assistantRunService;
    private final OrderCall orderCall;

    @Transactional(rollbackFor = Exception.class)
    public AssistantActionResultVo approve(String runId, String actionId) {
        AiAction action = assistantRunService.getAction(runId, actionId);
        if (action == null || !AssistantActionStatus.WAITING.name().equals(action.getActionStatus())) {
            throw new RuntimeException("没有可审批的下单动作");
        }
        assistantRunService.markActionApproved(action);
        Map<String, Object> preview = JSON.parseObject(action.getPreviewJson());
        ProgramOrderCreateDto orderCreateDto = JSON.parseObject(JSON.toJSONString(preview.get("programOrderCreateDto")), ProgramOrderCreateDto.class);
        String orderNumber = orderCall.createOrder(orderCreateDto);

        AssistantActionResultVo resultVo = new AssistantActionResultVo();
        resultVo.setActionId(action.getActionId());
        resultVo.setStatus(AssistantActionStatus.COMPLETED.name());
        resultVo.setMessage("订单已创建");
        resultVo.setOrderNumber(orderNumber);
        resultVo.setOrderListAddress(DaMaiConstant.ORDER_LIST_ADDRESS);
        assistantRunService.markActionCompleted(action, resultVo);

        AiRun run = assistantRunService.getRun(runId);
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
    }

    @Transactional(rollbackFor = Exception.class)
    public AssistantActionResultVo reject(String runId, String actionId) {
        AiAction action = assistantRunService.getAction(runId, actionId);
        if (action == null || !AssistantActionStatus.WAITING.name().equals(action.getActionStatus())) {
            throw new RuntimeException("没有可拒绝的下单动作");
        }
        AssistantActionResultVo resultVo = new AssistantActionResultVo();
        resultVo.setActionId(action.getActionId());
        resultVo.setStatus(AssistantActionStatus.REJECTED.name());
        resultVo.setMessage("已取消本次下单请求");
        assistantRunService.markActionRejected(action, resultVo);
        AiRun run = assistantRunService.getRun(runId);
        assistantRunService.markCompleted(run, "ACTION_REJECTED", "用户取消下单");
        assistantRunService.appendEvent(runId, AssistantEventTypes.RUN_COMPLETED, Map.of(
                "runId", runId,
                "status", AssistantActionStatus.REJECTED.name()
        ));
        return resultVo;
    }
}
