package org.javaup.ai.controller;

import lombok.RequiredArgsConstructor;
import org.javaup.ai.assistant.skill.ops.OpsProviderRegistry;
import org.javaup.ai.assistant.skill.ops.OpsRcaEvidenceService;
import org.javaup.ai.assistant.skill.ops.OpsRcaRequest;
import org.javaup.ai.common.ApiResponse;
import org.javaup.ai.entity.AiOpsRunbook;
import org.javaup.ai.mapper.AiOpsRunbookMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/assistant/admin/ops")
public class AiOpsAdminController {

    private final OpsRcaEvidenceService rcaEvidenceService;
    private final OpsProviderRegistry providerRegistry;
    private final AiOpsRunbookMapper runbookMapper;

    @PostMapping("/rca-evidence")
    public ApiResponse<Map<String, Object>> buildRcaEvidence(@RequestBody(required = false) OpsRcaRequest request) {
        return ApiResponse.ok(rcaEvidenceService.buildEvidenceBundle(request));
    }

    @GetMapping("/providers")
    public ApiResponse<List<Map<String, Object>>> providers() {
        return ApiResponse.ok(providerRegistry.providerStatuses());
    }

    @GetMapping("/runbooks")
    public ApiResponse<List<AiOpsRunbook>> runbooks() {
        return ApiResponse.ok(runbookMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(AiOpsRunbook.class)
                .eq(AiOpsRunbook::getStatus, 1)
                .eq(AiOpsRunbook::getRunbookStatus, "ACTIVE")
                .orderByAsc(AiOpsRunbook::getRiskLevel)));
    }
}
