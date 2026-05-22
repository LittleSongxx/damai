package org.javaup.ai.ai.function.dto;

import lombok.Data;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Date;

/**
 * 节目搜索查询参数 —— 遵循 Anthropic ACI 设计: 参数自文档化 + 格式约束。
 */
@Data
public class ProgramSearchFunctionDto {

    @ToolParam(required = false, description = """
            演出城市名称。支持中文城市名（如"北京"、"上海"、"杭州"）。
            LLM 需将用户口语转换为标准城市名（如"帝都"→"北京"、"魔都"→"上海"）。""")
    private String cityName;

    @ToolParam(required = false, description = """
            艺人/明星/演出团体名称。支持中文名（如"周杰伦"、"五月天"）、
            英文名（如"Taylor Swift"）。仅支持精确匹配，不支持模糊音或别名。""")
    private String actor;

    @ToolParam(required = false, description = """
            演出日期。格式: yyyy-MM-dd（如"2026-05-21"）。
            仅精确日期匹配，不支持范围查询。""")
    private Date showTime;
}
