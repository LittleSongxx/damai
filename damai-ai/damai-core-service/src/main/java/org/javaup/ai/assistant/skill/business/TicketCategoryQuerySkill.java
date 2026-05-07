package org.javaup.ai.assistant.skill.business;

import org.javaup.ai.assistant.AssistantRouteType;
import org.javaup.ai.assistant.AssistantSkill;
import org.javaup.ai.assistant.AssistantSkillContext;
import org.javaup.ai.assistant.AssistantSkillDescriptor;
import org.javaup.ai.assistant.AssistantSkillResult;
import org.javaup.ai.assistant.AssistantSkillRiskLevel;
import org.javaup.ai.ai.function.dto.ProgramSearchFunctionDto;
import org.javaup.ai.assistant.tool.AssistantToolInvoker;
import org.javaup.ai.vo.AssistantProgramDetailView;
import org.javaup.ai.vo.AssistantTicketCategoryView;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
public class TicketCategoryQuerySkill implements AssistantSkill {

    private final ProgramQueryService programQueryService;
    private final BusinessSkillParameterExtractor parameterExtractor;
    private final AssistantToolInvoker toolInvoker;

    public TicketCategoryQuerySkill(ProgramQueryService programQueryService,
                                    BusinessSkillParameterExtractor parameterExtractor,
                                    AssistantToolInvoker toolInvoker) {
        this.programQueryService = programQueryService;
        this.parameterExtractor = parameterExtractor;
        this.toolInvoker = toolInvoker;
    }

    @Override
    public AssistantRouteType routeType() {
        return AssistantRouteType.BUSINESS;
    }

    @Override
    public AssistantSkillDescriptor descriptor() {
        return AssistantSkillDescriptor.builder()
                .skillId("business.ticket.category.query")
                .name("票档查询")
                .description("查询节目票档、价格、库存状态和可购票档建议。")
                .version("1.0.0")
                .goal("查询节目票档价格和安全可展示的可购状态。")
                .instructions("不得输出具体余票数量，只能展示 SOLD_OUT、LOW_STOCK、AVAILABLE 或 UNKNOWN 等状态。")
                .routeType(AssistantRouteType.BUSINESS)
                .category("business")
                .triggerKeywords(List.of("票档", "价格", "库存", "余票", "多少钱", "座位", "票价"))
                .toolAllowlist(List.of("getProgramDetail"))
                .examples(List.of("周杰伦北京演唱会有哪些票档", "这个节目多少钱"))
                .evalCases(List.of("票档查询不能输出具体余票数字"))
                .inputSchemaJson("""
                        {"type":"object","required":["message"],"properties":{"message":{"type":"string"}}}
                        """)
                .outputSchemaJson("""
                        {"type":"object","required":["message"],"properties":{"message":{"type":"string"}}}
                        """)
                .riskLevel(AssistantSkillRiskLevel.LOW)
                .requiresAdmin(false)
                .requiresApproval(false)
                .enabled(true)
                .executorType("java")
                .frontendSelectable(true)
                .modelSelectable(true)
                .primarySkill(false)
                .build();
    }

    @Override
    public AssistantSkillResult execute(AssistantSkillContext context) {
        ProgramSearchFunctionDto request = parameterExtractor.extractSearch(context.getMessage());
        AssistantProgramDetailView detail = toolInvoker.invoke(context.getRun().getRunId(), "getProgramDetail", "business", request,
                () -> programQueryService.getProgramDetail(request));
        String answer = render(detail);
        return AssistantSkillResult.builder()
                .message(answer)
                .responseSummary(answer)
                .build();
    }

    private String render(AssistantProgramDetailView detail) {
        if (detail == null) {
            return "没有查询到对应节目的票档信息。请补充城市、艺人或演出时间后再试。";
        }
        List<AssistantTicketCategoryView> tickets = detail.getTicketCategories();
        if (tickets == null || tickets.isEmpty()) {
            return "《%s》暂无可展示票档。".formatted(safe(detail.getTitle()));
        }
        String rows = tickets.stream()
                .map(ticket -> "- %s｜价格 %s｜状态 %s"
                        .formatted(safe(ticket.getIntroduce()),
                                ticket.getPrice() == null ? "未知" : ticket.getPrice(),
                                safe(ticket.getAvailabilityStatus())))
                .collect(Collectors.joining("\n"));
        return "《%s》的安全可展示票档如下：\n%s".formatted(safe(detail.getTitle()), rows);
    }

    private String safe(String value) {
        return value == null ? "未知" : value;
    }
}
