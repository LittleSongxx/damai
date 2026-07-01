package org.javaup.ai.assistant.mcp.tool;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.assistant.mcp.McpToolGovernanceService;
import org.javaup.ai.assistant.skill.ops.nl2sql.Nl2SqlOrchestrator;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class Nl2SqlMcpTools {

    private final Nl2SqlOrchestrator nl2SqlOrchestrator;
    private final McpToolGovernanceService governanceService;

    @Tool(description = "通过自然语言查询数据库，将中文问题转换为SQL并执行查询。" +
            "适用于：统计订单量、查询销售额、分析支付成功率、查看退款情况等数据统计场景。" +
            "示例问题：「最近30天各项目的订单量统计」「上个月支付成功率是多少」「退款最多的5个项目」")
    public Map<String, Object> nl2sqlQuery(
            @ToolParam(description = "用自然语言描述的数据查询需求，如「统计近7天每天的订单总数」") String question) {
        return governanceService.execute("nl2sql.nl2sqlQuery",
                Map.of("question", question == null ? "" : question),
                () -> nl2SqlOrchestrator.answer("mcp-nl2sql", question, "mcp-nl2sql"));
    }
}
