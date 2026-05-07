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
public class ProgramDetailSkill implements AssistantSkill {

    private final ProgramQueryService programQueryService;
    private final BusinessSkillParameterExtractor parameterExtractor;
    private final AssistantToolInvoker toolInvoker;

    public ProgramDetailSkill(ProgramQueryService programQueryService,
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
                .skillId("business.program.detail")
                .name("节目详情查询")
                .description("查询节目时间、场馆、艺人、票务状态和基础详情。")
                .version("1.0.0")
                .goal("查询单个真实节目的安全可展示详情。")
                .instructions("必须基于节目详情工具返回内容回答；票档只展示价格和可购状态，不展示具体余票数量。")
                .routeType(AssistantRouteType.BUSINESS)
                .category("business")
                .triggerKeywords(List.of("详情", "时间", "场馆", "地址", "艺人", "什么时候", "在哪"))
                .toolAllowlist(List.of("getProgramDetail"))
                .examples(List.of("周杰伦北京演唱会什么时候开始", "这个节目在哪个场馆"))
                .evalCases(List.of("详情查询不应调用下单工具"))
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
            return "没有查询到对应节目的真实详情。请补充城市、艺人或演出时间后再试。";
        }
        String tickets = detail.getTicketCategories() == null || detail.getTicketCategories().isEmpty()
                ? "暂无可展示票档"
                : detail.getTicketCategories().stream()
                .map(this::ticketLine)
                .collect(Collectors.joining("\n"));
        return """
                《%s》
                艺人：%s
                城市：%s
                场馆：%s
                时间：%s

                票档：
                %s
                """.formatted(safe(detail.getTitle()), safe(detail.getActor()), safe(detail.getAreaName()),
                safe(detail.getPlace()), detail.getShowTime() == null ? "待确认" : detail.getShowTime(), tickets).trim();
    }

    private String ticketLine(AssistantTicketCategoryView ticket) {
        return "- %s｜价格 %s｜%s".formatted(safe(ticket.getIntroduce()),
                ticket.getPrice() == null ? "未知" : ticket.getPrice(),
                safe(ticket.getAvailabilityStatus()));
    }

    private String safe(String value) {
        return value == null ? "未知" : value;
    }
}
