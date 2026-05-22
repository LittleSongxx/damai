package org.javaup.ai.ai.function.dto;

import lombok.Data;
import org.springframework.ai.tool.annotation.ToolParam;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * 购票预览参数 —— 遵循 Anthropic ACI 设计: 参数自文档化 + poka-yoke 防错。
 *
 * <p>注意: 此工具仅生成购票预览并等待用户审批，不会直接创建订单。
 */
@Data
public class CreateOrderFunctionDto {

    @ToolParam(required = true, description = """
            演出城市。标准中文城市名，如"北京"、"上海"、"杭州"。
            必须与 searchPrograms/getProgramDetail 返回的城市名一致，不得编造。""")
    private String cityName;

    @ToolParam(required = true, description = """
            艺人名称。必须与 searchPrograms/getProgramDetail 返回的艺人名完全一致，
            不得编造、缩写或翻译。""")
    private String actor;

    @ToolParam(required = false, description = """
            演出日期，格式 yyyy-MM-dd（如"2026-05-21"）。
            必须来自 getProgramDetail 返回的实际场次，不得编造日期。""")
    private Date showTime;

    @ToolParam(required = true, description = """
            用户手机号。格式: 11位中国大陆手机号（1开头的纯数字）。
            错误示例: "1380013800"（10位）,"21380013800"（非1开头）。""")
    private String mobile;

    @ToolParam(required = true, description = """
            购票人证件号码列表。每个元素为18位身份证号或护照号。
            成人购票至少1个证件号，不得超过6个（每人限购6张）。""")
    private List<String> ticketUserNumberList;

    @ToolParam(required = true, description = """
            票档价位（元）。必须来自 getProgramDetail 返回的实际票档价格之一，
            不得编造价格。如: 280、480、680、880、1280。""")
    private BigDecimal ticketCategoryPrice;

    @ToolParam(required = true, description = """
            购买数量，正整数。不得超过 ticketUserNumberList 的长度（一人一票），
            且单次不超过6张。""")
    private Integer ticketCount;
}
