package org.javaup.ai.assistant.skill.business;

import org.javaup.ai.ai.function.dto.CreateOrderFunctionDto;
import org.javaup.ai.ai.function.dto.ProgramRecommendFunctionDto;
import org.javaup.ai.ai.function.dto.ProgramSearchFunctionDto;
import org.javaup.ai.assistant.tool.AssistantToolInvoker;
import org.javaup.ai.vo.AssistantActionPreviewVo;
import org.javaup.ai.vo.AssistantProgramDetailView;
import org.javaup.ai.vo.ProgramSearchVo;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

@Component
public class BusinessToolService {

    private final ProgramQueryService programQueryService;
    private final PurchasePreparationService purchasePreparationService;
    private final AssistantToolInvoker toolInvoker;

    public BusinessToolService(ProgramQueryService programQueryService,
                               PurchasePreparationService purchasePreparationService,
                               AssistantToolInvoker toolInvoker) {
        this.programQueryService = programQueryService;
        this.purchasePreparationService = purchasePreparationService;
        this.toolInvoker = toolInvoker;
    }

    @Tool(description = "根据地区或者类型查询推荐的节目")
    public List<ProgramSearchVo> recommendPrograms(@ToolParam(description = "查询条件", required = true) ProgramRecommendFunctionDto request) {
        return recordTool("recommendPrograms", request, () -> programQueryService.recommendPrograms(request));
    }

    @Tool(description = "根据条件查询节目")
    public List<ProgramSearchVo> searchPrograms(@ToolParam(description = "查询条件", required = true) ProgramSearchFunctionDto request) {
        return recordTool("searchPrograms", request, () -> programQueryService.searchPrograms(request));
    }

    @Tool(description = "查询节目的详情和安全可展示的票档信息")
    public AssistantProgramDetailView getProgramDetail(@ToolParam(description = "查询条件", required = true) ProgramSearchFunctionDto request) {
        return recordTool("getProgramDetail", request, () -> programQueryService.getProgramDetail(request));
    }

    @Tool(description = "生成购票预览并等待用户审批，不能直接创建订单")
    public AssistantActionPreviewVo preparePurchase(@ToolParam(description = "购票信息", required = true) CreateOrderFunctionDto request) {
        return recordTool("preparePurchase", request, () -> purchasePreparationService.prepare(request));
    }

    private <T> T recordTool(String toolName, Object input, Supplier<T> supplier) {
        String runId = currentRunId();
        return toolInvoker.invoke(runId, toolName, "business", input, supplier::get);
    }

    private String currentRunId() {
        return org.javaup.ai.context.AiRequestContextHolder.getOptional()
                .map(context -> context.getRunId())
                .orElseThrow(() -> new IllegalStateException("AI run context is missing"));
    }
}
