package org.javaup.ai.controller;

import lombok.RequiredArgsConstructor;
import org.javaup.ai.assistant.AssistantSkillManagementService;
import org.javaup.ai.assistant.mcp.McpBoundaryService;
import org.javaup.ai.assistant.mcp.McpToolGovernanceService;
import org.javaup.ai.common.ApiResponse;
import org.javaup.ai.context.AiRequestContextHolder;
import org.javaup.ai.dto.AssistantSkillUpdateRequest;
import org.javaup.ai.service.AiQualityGateService;
import org.javaup.ai.service.AssistantEvalRunService;
import org.javaup.ai.vo.AssistantEvalRunRequest;
import org.javaup.ai.vo.AssistantEvalRunVo;
import org.javaup.ai.vo.AssistantSkillEvalRunVo;
import org.javaup.ai.vo.AssistantSkillVo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/assistant")
public class AssistantGovernanceController {

    private final AssistantSkillManagementService skillManagementService;
    private final AiQualityGateService qualityGateService;
    private final McpToolGovernanceService mcpToolGovernanceService;
    private final McpBoundaryService mcpBoundaryService;
    private final AssistantEvalRunService assistantEvalRunService;

    @PatchMapping("/admin/skills/{skillId}")
    public ApiResponse<AssistantSkillVo> updateSkill(@PathVariable("skillId") String skillId,
                                                     @RequestBody AssistantSkillUpdateRequest request) {
        return ApiResponse.ok(skillManagementService.updateSkill(skillId, request,
                AiRequestContextHolder.getRequiredUser()));
    }

    @PostMapping("/admin/skills/{skillId}/eval-runs")
    public ApiResponse<AssistantSkillEvalRunVo> createSkillEvalRun(@PathVariable("skillId") String skillId) {
        return ApiResponse.ok(skillManagementService.createEvalRun(skillId,
                AiRequestContextHolder.getRequiredUser()));
    }

    @PostMapping("/evals/{suite}/run")
    public ApiResponse<AssistantEvalRunVo> runEvalSuite(@PathVariable("suite") String suite,
                                                        @RequestBody(required = false) AssistantEvalRunRequest request) {
        return ApiResponse.ok(assistantEvalRunService.runSuite(suite, request));
    }

    @GetMapping("/evals/{suite}/runs/{evalRunId}")
    public ApiResponse<AssistantEvalRunVo> getEvalRun(@PathVariable("suite") String suite,
                                                      @PathVariable("evalRunId") String evalRunId) {
        AssistantEvalRunVo run = assistantEvalRunService.getSuiteRun(suite, evalRunId);
        return run == null ? ApiResponse.error("Eval run 不存在") : ApiResponse.ok(run);
    }

    @GetMapping("/admin/quality-gates/latest")
    public ApiResponse<Map<String, Object>> latestQualityGate() {
        return ApiResponse.ok(qualityGateService.latestGate());
    }

    @GetMapping("/admin/mcp/governance")
    public ApiResponse<Map<String, Object>> mcpGovernance() {
        return ApiResponse.ok(mcpToolGovernanceService.governanceSnapshot());
    }

    @PostMapping("/admin/mcp/resources/read")
    public ApiResponse<Map<String, Object>> readMcpResource(@RequestBody Map<String, Object> body) {
        String resourceUri = body == null ? "" : String.valueOf(body.getOrDefault("resourceUri", ""));
        return ApiResponse.ok(mcpBoundaryService.readResource(resourceUri, body == null ? Map.of() : body));
    }

    @PostMapping("/admin/mcp/prompts/render")
    public ApiResponse<Map<String, Object>> renderMcpPrompt(@RequestBody Map<String, Object> body) {
        String promptName = body == null ? "" : String.valueOf(body.getOrDefault("promptName", ""));
        return ApiResponse.ok(mcpBoundaryService.renderPrompt(promptName, body == null ? Map.of() : body));
    }
}
