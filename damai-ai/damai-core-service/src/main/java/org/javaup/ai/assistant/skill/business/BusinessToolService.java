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

    @Tool(description = """
            推荐热门或优质的演出节目。适用于: 用户想浏览某城市/类型的演出但无明确艺人或场次偏好。
            何时使用: 用户说"推荐"、"有什么好看的"、"近期有什么演出"等开放式浏览请求。
            何时不用: 用户已指定艺人或明确搜索条件时，应使用 searchPrograms。
            返回: 节目列表，包含名称、艺人、时间、场馆、最低价格等信息。""")
    public List<ProgramSearchVo> recommendPrograms(@ToolParam(description = "查询条件", required = true) ProgramRecommendFunctionDto request) {
        return recordTool("recommendPrograms", request, () -> programQueryService.recommendPrograms(request));
    }

    @Tool(description = """
            按城市、艺人和/或日期精确搜索演出节目。适用于: 用户有明确的艺人名、城市或日期。
            何时使用: 用户说"搜一下"、"找"、"有没有XXX的演出"等定向搜索。
            何时不用: 用户无明确条件想浏览推荐时，使用 recommendPrograms。
            返回: 匹配的节目列表；无匹配时返回空列表（应告知用户未找到并建议调整条件）。""")
    public List<ProgramSearchVo> searchPrograms(@ToolParam(description = "查询条件", required = true) ProgramSearchFunctionDto request) {
        return recordTool("searchPrograms", request, () -> programQueryService.searchPrograms(request));
    }

    @Tool(description = """
            查询指定节目的详细信息，包括: 场次时间、场馆地址、座位图、可售票档及价格。
            前置条件: 必须先通过 searchPrograms 或 recommendPrograms 获取节目标识，
                      再调用此工具获取详情。不能凭空传入城市和艺人名。
            返回: 节目详情，包含票档列表（每个票档有价格和库存状态）。
            后续: 获取票档信息后，如果用户要购票，调用 preparePurchase 生成预览。""")
    public AssistantProgramDetailView getProgramDetail(@ToolParam(description = "查询条件", required = true) ProgramSearchFunctionDto request) {
        return recordTool("getProgramDetail", request, () -> programQueryService.getProgramDetail(request));
    }

    @Tool(description = """
            生成购票预览订单并等待用户确认，不会直接扣款或创建订单。
            前置条件: 必须先通过 getProgramDetail 获取真实票档和价格信息。
            参数来源: cityName/actor/showTime 必须与前面工具返回的数据完全一致；
                      ticketCategoryPrice 必须来自 getProgramDetail 返回的票档价格。
            返回: 购票预览（含订单摘要、支付金额、有效期），用户审批后才会执行后续流程。
            审批: 用户需在预览中点击"确认购买"或"取消"以完成审批。""")
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
