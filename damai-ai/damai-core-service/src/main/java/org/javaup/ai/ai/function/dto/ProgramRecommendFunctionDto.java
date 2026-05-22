package org.javaup.ai.ai.function.dto;

import lombok.Data;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 节目推荐查询参数 —— 遵循 Anthropic ACI 设计: 参数自文档化 + 格式约束。
 */
@Data
public class ProgramRecommendFunctionDto {

    @ToolParam(required = false, description = """
            演出城市或地区名称。支持: 城市全称（如"北京"、"上海"）、省份（如"浙江"）、
            区域（如"全国"、"华东"）。留空则推荐全国热门演出。""")
    private String areaName;

    @ToolParam(required = false, description = """
            节目类型/分类。支持: "演唱会"、"音乐会"、"话剧"、"舞蹈"、"戏曲"、
            "脱口秀"、"儿童剧"、"体育赛事"、"展览"等。留空则推荐所有类型。""")
    private String programCategory;
}
