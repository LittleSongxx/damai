package org.javaup.ai.controller;

import lombok.RequiredArgsConstructor;
import org.javaup.ai.assistant.AssistantSkillManagementService;
import org.javaup.ai.assistant.AssistantRuntimeService;
import org.javaup.ai.assistant.mcp.McpBoundaryService;
import org.javaup.ai.assistant.mcp.McpToolGovernanceService;
import org.javaup.ai.assistant.runtime.AssistantRunGraphService;
import org.javaup.ai.common.ApiResponse;
import org.javaup.ai.context.AiRequestContextHolder;
import org.javaup.ai.dto.AssistantRunCreateRequest;
import org.javaup.ai.dto.AssistantSkillUpdateRequest;
import org.javaup.ai.service.AssistantEvalRunService;
import org.javaup.ai.service.AiQualityGateService;
import org.javaup.ai.vo.AssistantActionResultVo;
import org.javaup.ai.vo.AssistantConversationVo;
import org.javaup.ai.vo.AssistantEvalRunRequest;
import org.javaup.ai.vo.AssistantEvalRunVo;
import org.javaup.ai.vo.AssistantRunCreatedVo;
import org.javaup.ai.vo.AssistantRunDetailVo;
import org.javaup.ai.vo.AssistantRunGraphVo;
import org.javaup.ai.vo.AssistantRunReplayVo;
import org.javaup.ai.vo.AssistantSkillDetailVo;
import org.javaup.ai.vo.AssistantSkillEvalRunVo;
import org.javaup.ai.vo.AssistantSkillVo;
import org.javaup.ai.vo.AiUserCapabilitiesVo;
import org.javaup.ai.vo.ChatHistoryMessageVO;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/assistant")
public class AssistantController {

    private final AssistantRuntimeService assistantRuntimeService;
    private final AssistantSkillManagementService skillManagementService;
    private final AssistantRunGraphService runGraphService;
    private final AiQualityGateService qualityGateService;
    private final McpToolGovernanceService mcpToolGovernanceService;
    private final McpBoundaryService mcpBoundaryService;
    private final AssistantEvalRunService assistantEvalRunService;

    @GetMapping("/capabilities")
    public ApiResponse<AiUserCapabilitiesVo> getCapabilities() {
        return ApiResponse.ok(assistantRuntimeService.getCurrentUserCapabilities(AiRequestContextHolder.getRequiredUser()));
    }

    @GetMapping("/skills")
    public ApiResponse<List<AssistantSkillVo>> listSkills() {
        return ApiResponse.ok(skillManagementService.listSkills(AiRequestContextHolder.getRequiredUser()));
    }

    @GetMapping("/skills/{skillId}")
    public ApiResponse<AssistantSkillDetailVo> getSkill(@PathVariable("skillId") String skillId) {
        return ApiResponse.ok(skillManagementService.getSkill(skillId, AiRequestContextHolder.getRequiredUser()));
    }

    @PatchMapping("/admin/skills/{skillId}")
    public ApiResponse<AssistantSkillVo> updateSkill(@PathVariable("skillId") String skillId,
                                                     @RequestBody AssistantSkillUpdateRequest request) {
        return ApiResponse.ok(skillManagementService.updateSkill(skillId, request, AiRequestContextHolder.getRequiredUser()));
    }

    @PostMapping("/admin/skills/{skillId}/eval-runs")
    public ApiResponse<AssistantSkillEvalRunVo> createSkillEvalRun(@PathVariable("skillId") String skillId) {
        return ApiResponse.ok(skillManagementService.createEvalRun(skillId, AiRequestContextHolder.getRequiredUser()));
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
    public ApiResponse<java.util.Map<String, Object>> latestQualityGate() {
        return ApiResponse.ok(qualityGateService.latestGate());
    }

    @GetMapping("/admin/mcp/governance")
    public ApiResponse<java.util.Map<String, Object>> mcpGovernance() {
        return ApiResponse.ok(mcpToolGovernanceService.governanceSnapshot());
    }

    @PostMapping("/admin/mcp/resources/read")
    public ApiResponse<java.util.Map<String, Object>> readMcpResource(@RequestBody java.util.Map<String, Object> body) {
        String resourceUri = body == null ? "" : String.valueOf(body.getOrDefault("resourceUri", ""));
        return ApiResponse.ok(mcpBoundaryService.readResource(resourceUri, body == null ? java.util.Map.of() : body));
    }

    @PostMapping("/admin/mcp/prompts/render")
    public ApiResponse<java.util.Map<String, Object>> renderMcpPrompt(@RequestBody java.util.Map<String, Object> body) {
        String promptName = body == null ? "" : String.valueOf(body.getOrDefault("promptName", ""));
        return ApiResponse.ok(mcpBoundaryService.renderPrompt(promptName, body == null ? java.util.Map.of() : body));
    }

    @PostMapping("/runs")
    public ApiResponse<AssistantRunCreatedVo> createRun(@RequestBody AssistantRunCreateRequest request) {
        return ApiResponse.ok(assistantRuntimeService.createRun(request));
    }

    @GetMapping(value = "/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamRun(@PathVariable("runId") String runId) {
        return assistantRuntimeService.streamRun(runId);
    }

    @GetMapping("/runs/{runId}")
    public ApiResponse<AssistantRunDetailVo> getRun(@PathVariable("runId") String runId) {
        return ApiResponse.ok(assistantRuntimeService.getRunDetail(runId));
    }

    @GetMapping("/runs/{runId}/graph")
    public ApiResponse<AssistantRunGraphVo> getRunGraph(@PathVariable("runId") String runId) {
        AssistantRunGraphVo graph = runGraphService.buildGraph(runId);
        return graph == null ? ApiResponse.error("Run 不存在") : ApiResponse.ok(graph);
    }

    @PostMapping("/runs/{runId}/resume")
    public ApiResponse<AssistantRunCreatedVo> resumeRun(@PathVariable("runId") String runId) {
        return ApiResponse.ok(assistantRuntimeService.resumeRun(runId));
    }

    @PostMapping("/runs/{runId}/replay")
    public ApiResponse<AssistantRunReplayVo> replayRun(@PathVariable("runId") String runId) {
        return ApiResponse.ok(assistantRuntimeService.replayRun(runId));
    }

    @PostMapping("/runs/{runId}/actions/{actionId}/approve")
    public ApiResponse<AssistantActionResultVo> approve(@PathVariable("runId") String runId,
                                                        @PathVariable("actionId") String actionId) {
        return ApiResponse.ok(assistantRuntimeService.approveAction(runId, actionId));
    }

    @PostMapping("/runs/{runId}/actions/{actionId}/reject")
    public ApiResponse<AssistantActionResultVo> reject(@PathVariable("runId") String runId,
                                                       @PathVariable("actionId") String actionId) {
        return ApiResponse.ok(assistantRuntimeService.rejectAction(runId, actionId));
    }

    @GetMapping("/conversations")
    public ApiResponse<List<AssistantConversationVo>> getConversations() {
        return ApiResponse.ok(assistantRuntimeService.listConversations());
    }

    @GetMapping("/conversations/{chatId}/messages")
    public ApiResponse<List<ChatHistoryMessageVO>> getMessages(@PathVariable("chatId") String chatId) {
        return ApiResponse.ok(assistantRuntimeService.listMessages(chatId));
    }
}
