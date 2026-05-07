package org.javaup.ai.assistant.skill.business;

import org.javaup.ai.assistant.AssistantRouteType;
import org.javaup.ai.assistant.AssistantSkill;
import org.javaup.ai.assistant.AssistantSkillContext;
import org.javaup.ai.assistant.AssistantSkillDescriptor;
import org.javaup.ai.assistant.AssistantSkillResult;
import org.javaup.ai.assistant.AssistantSkillRiskLevel;
import org.javaup.ai.ai.function.dto.CreateOrderFunctionDto;
import org.javaup.ai.assistant.AssistantRunService;
import org.javaup.ai.assistant.tool.AssistantToolInvoker;
import org.javaup.ai.entity.AiAction;
import org.javaup.ai.vo.AssistantActionPreviewVo;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PurchasePrepareSkill implements AssistantSkill {

    private final PurchasePreparationService purchasePreparationService;
    private final BusinessSkillParameterExtractor parameterExtractor;
    private final AssistantToolInvoker toolInvoker;
    private final AssistantRunService assistantRunService;

    public PurchasePrepareSkill(PurchasePreparationService purchasePreparationService,
                                BusinessSkillParameterExtractor parameterExtractor,
                                AssistantToolInvoker toolInvoker,
                                AssistantRunService assistantRunService) {
        this.purchasePreparationService = purchasePreparationService;
        this.parameterExtractor = parameterExtractor;
        this.toolInvoker = toolInvoker;
        this.assistantRunService = assistantRunService;
    }

    @Override
    public AssistantRouteType routeType() {
        return AssistantRouteType.BUSINESS;
    }

    @Override
    public AssistantSkillDescriptor descriptor() {
        return AssistantSkillDescriptor.builder()
                .skillId("business.purchase.prepare")
                .name("购票准备")
                .description("根据用户购票目标准备下单信息，并通过既有审批动作完成人在环确认。")
                .version("1.0.0")
                .goal("根据用户明确提供的节目、票档、数量和购票人信息生成下单预览。")
                .instructions("只能生成购票预览和待审批动作，不得直接创建订单；信息不足时提示用户补全。")
                .routeType(AssistantRouteType.BUSINESS)
                .category("business")
                .triggerKeywords(List.of("买", "下单", "购买", "帮我订", "两张", "付款", "抢票"))
                .toolAllowlist(List.of("preparePurchase"))
                .examples(List.of("帮我买两张北京周杰伦演唱会 680 元票档，手机号和购票人证件号如下..."))
                .evalCases(List.of("购票准备必须生成待审批动作"))
                .inputSchemaJson("""
                        {"type":"object","required":["message"],"properties":{"message":{"type":"string"}}}
                        """)
                .outputSchemaJson("""
                        {"type":"object","required":["message"],"properties":{"message":{"type":"string"}}}
                        """)
                .riskLevel(AssistantSkillRiskLevel.MEDIUM)
                .requiresAdmin(false)
                .requiresApproval(true)
                .enabled(true)
                .executorType("java")
                .frontendSelectable(true)
                .modelSelectable(true)
                .primarySkill(false)
                .build();
    }

    @Override
    public AssistantSkillResult execute(AssistantSkillContext context) {
        CreateOrderFunctionDto request = parameterExtractor.extractPurchase(context.getMessage());
        AssistantActionPreviewVo preview = toolInvoker.invoke(context.getRun().getRunId(), "preparePurchase", "business", request,
                () -> purchasePreparationService.prepare(request));
        AiAction pendingAction = assistantRunService.getPendingAction(context.getRun().getRunId());
        String answer = preview == null || preview.getPreviewSummary() == null
                ? "购票预览已生成，请确认后再正式创建订单。"
                : preview.getPreviewSummary();
        return AssistantSkillResult.builder()
                .message(answer)
                .responseSummary(answer)
                .pendingAction(pendingAction)
                .build();
    }
}
