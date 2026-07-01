package org.javaup.ai.controller;

import lombok.RequiredArgsConstructor;
import org.javaup.ai.assistant.skill.ops.AiOpsFaultInjectionService;
import org.javaup.ai.assistant.skill.ops.AiOpsFaultScenario;
import org.javaup.ai.assistant.skill.ops.OpsRcaEvidenceService;
import org.javaup.ai.assistant.skill.ops.OpsRcaRequest;
import org.javaup.ai.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/assistant/admin/aiops")
public class AiOpsAdminController {

    private final AiOpsFaultInjectionService faultInjectionService;

    private final OpsRcaEvidenceService rcaEvidenceService;

    @GetMapping("/fault-scenarios")
    public ApiResponse<List<AiOpsFaultScenario>> listFaultScenarios() {
        return ApiResponse.ok(faultInjectionService.listScenarios());
    }

    @PostMapping("/fault-scenarios/{scenarioId}/inject")
    public ApiResponse<Map<String, Object>> injectFaultScenario(@PathVariable String scenarioId,
                                                                @RequestBody(required = false) OpsRcaRequest request) {
        Map<String, Object> result = faultInjectionService.injectScenario(scenarioId, request);
        if ("NOT_FOUND".equals(result.get("status"))) {
            return ApiResponse.error("故障场景不存在");
        }
        return ApiResponse.ok(result);
    }

    @PostMapping("/rca-evidence")
    public ApiResponse<Map<String, Object>> buildRcaEvidence(@RequestBody(required = false) OpsRcaRequest request) {
        return ApiResponse.ok(rcaEvidenceService.buildEvidenceBundle(request));
    }
}
