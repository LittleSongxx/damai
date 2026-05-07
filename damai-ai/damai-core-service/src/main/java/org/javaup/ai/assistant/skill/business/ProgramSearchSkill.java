package org.javaup.ai.assistant.skill.business;

import org.javaup.ai.assistant.AssistantRouteType;
import org.javaup.ai.assistant.AssistantSkill;
import org.javaup.ai.assistant.AssistantSkillContext;
import org.javaup.ai.assistant.AssistantSkillDescriptor;
import org.javaup.ai.assistant.AssistantSkillResult;
import org.javaup.ai.assistant.AssistantSkillRiskLevel;
import org.javaup.ai.ai.function.dto.ProgramRecommendFunctionDto;
import org.javaup.ai.ai.function.dto.ProgramSearchFunctionDto;
import org.javaup.ai.assistant.tool.AssistantToolInvoker;
import org.javaup.ai.vo.ProgramSearchVo;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class ProgramSearchSkill implements AssistantSkill {

    private final ProgramQueryService programQueryService;
    private final BusinessSkillParameterExtractor parameterExtractor;
    private final AssistantToolInvoker toolInvoker;

    public ProgramSearchSkill(ProgramQueryService programQueryService,
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
                .skillId("business.program.search")
                .name("节目搜索")
                .description("按城市、时间、艺人、类型和价格偏好搜索或推荐演出节目。")
                .version("1.0.0")
                .goal("根据用户给出的城市、艺人、类型或时间偏好搜索和推荐真实节目。")
                .instructions("只返回工具查询到的节目数据；如果缺少条件或没有结果，应提示用户补充城市、艺人、类型或时间。")
                .routeType(AssistantRouteType.BUSINESS)
                .category("business")
                .triggerKeywords(List.of("找", "搜索", "推荐", "演出", "节目", "演唱会", "脱口秀", "城市", "周末"))
                .toolAllowlist(List.of("searchPrograms", "recommendPrograms"))
                .examples(List.of("帮我推荐北京周末的演唱会", "搜索上海最近的脱口秀"))
                .evalCases(List.of("推荐北京演唱会时只应调用搜索或推荐工具"))
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
        String message = context.getMessage();
        boolean recommendation = message != null && (message.contains("推荐") || message.contains("随便") || message.contains("有什么"));
        List<ProgramSearchVo> programs;
        String toolName;
        Object request;
        if (recommendation) {
            ProgramRecommendFunctionDto recommendRequest = parameterExtractor.extractRecommend(message);
            request = recommendRequest;
            toolName = "recommendPrograms";
            programs = toolInvoker.invoke(context.getRun().getRunId(), toolName, "business", request,
                    () -> programQueryService.recommendPrograms(recommendRequest));
        } else {
            ProgramSearchFunctionDto searchRequest = parameterExtractor.extractSearch(message);
            request = searchRequest;
            toolName = "searchPrograms";
            programs = toolInvoker.invoke(context.getRun().getRunId(), toolName, "business", request,
                    () -> programQueryService.searchPrograms(searchRequest));
        }
        String answer = render(programs, toolName);
        return AssistantSkillResult.builder()
                .message(answer)
                .responseSummary(answer)
                .build();
    }

    private String render(List<ProgramSearchVo> programs, String toolName) {
        if (programs == null || programs.isEmpty()) {
            return "没有查询到符合条件的真实节目。请补充城市、艺人、节目类型或演出时间后再试。";
        }
        String rows = programs.stream()
                .limit(6)
                .map(program -> "- 《%s》｜%s｜%s｜%s｜价格 %s-%s"
                        .formatted(safe(program.getTitle()), safe(program.getActor()), safe(program.getAreaName()),
                                program.getShowTime() == null ? "时间待确认" : program.getShowTime(),
                                program.getMinPrice() == null ? "未知" : program.getMinPrice(),
                                program.getMaxPrice() == null ? "未知" : program.getMaxPrice()))
                .collect(Collectors.joining("\n"));
        return ("recommendPrograms".equals(toolName) ? "根据当前条件，推荐这些节目：\n" : "查询到这些节目：\n") + rows;
    }

    private String safe(String value) {
        return value == null ? "未知" : value;
    }
}
