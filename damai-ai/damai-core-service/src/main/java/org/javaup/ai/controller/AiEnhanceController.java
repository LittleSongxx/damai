package org.javaup.ai.controller;

import jakarta.annotation.Resource;
import org.javaup.ai.common.ApiResponse;
import org.javaup.ai.entity.AiTrace;
import org.javaup.ai.security.AiPermissionService;
import org.javaup.ai.service.AiObservabilityService;
import org.javaup.ai.vo.TokenStatisticsVo;
import org.javaup.ai.vo.TypeStatisticsVo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * @program: 大麦-ai智能服务项目。 添加 阿星不是程序员 微信，添加时备注 ai 来获取项目的完整资料 
 * @description: AI的观测功能
 * @author: 阿星不是程序员
 **/
@RestController
@RequestMapping("/assistant/admin")
public class AiEnhanceController {
    
    @Resource
    private AiObservabilityService observabilityService;

    @Resource
    private AiPermissionService aiPermissionService;
    
    @GetMapping("/observability/today")
    public ApiResponse<TokenStatisticsVo> getTodayStats() {
        aiPermissionService.requireOpsAccess();
        TokenStatisticsVo stats = observabilityService.getTodayStats();
        return ApiResponse.ok(stats);
    }
    
    @GetMapping("/observability/conversation")
    public ApiResponse<TokenStatisticsVo> getConversationStats(@RequestParam("conversationId") String conversationId) {
        aiPermissionService.requireOpsAccess();
        TokenStatisticsVo stats = observabilityService.getConversationStats(conversationId);
        return ApiResponse.ok(stats);
    }
    
    @GetMapping("/observability/traces")
    public ApiResponse<List<AiTrace>> getRecentTraces(@RequestParam(value = "limit", defaultValue = "50") int limit) {
        aiPermissionService.requireOpsAccess();
        List<AiTrace> traces = observabilityService.getRecentTraces(limit);
        return ApiResponse.ok(traces);
    }
    
    @GetMapping("/observability/stats/type")
    public ApiResponse<List<TypeStatisticsVo>> getStatsByType() {
        aiPermissionService.requireOpsAccess();
        List<TypeStatisticsVo> stats = observabilityService.getStatsByRequestType();
        return ApiResponse.ok(stats);
    }
}
