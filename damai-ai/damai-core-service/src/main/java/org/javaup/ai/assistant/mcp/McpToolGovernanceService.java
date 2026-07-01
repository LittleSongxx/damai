package org.javaup.ai.assistant.mcp;

import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.context.AiRequestContext;
import org.javaup.ai.context.AiRequestContextHolder;
import org.javaup.ai.entity.AiToolAudit;
import org.javaup.ai.mapper.AiToolAuditMapper;
import org.javaup.ai.security.AiAuthorizationException;
import org.javaup.ai.security.AiPermissionService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@Service
@RequiredArgsConstructor
public class McpToolGovernanceService {

    private final McpGovernanceProperties properties;
    private final AiPermissionService permissionService;
    private final AiToolAuditMapper toolAuditMapper;

    public <T> T execute(String toolName, Map<String, Object> request, Supplier<T> supplier) {
        authorize(toolName, request);
        long start = System.currentTimeMillis();
        try {
            T response = supplier.get();
            audit(toolName, request, response, true, null, System.currentTimeMillis() - start);
            return response;
        } catch (RuntimeException ex) {
            audit(toolName, request, null, false, ex.getMessage(), System.currentTimeMillis() - start);
            throw ex;
        }
    }

    public <T> T accessResource(String resourceUri, Map<String, Object> request, Supplier<T> supplier) {
        authorizeResource(resourceUri, request);
        long start = System.currentTimeMillis();
        String auditName = "resource:" + resourceUri;
        try {
            T response = supplier.get();
            audit(auditName, request, response, true, null, System.currentTimeMillis() - start);
            return response;
        } catch (RuntimeException ex) {
            audit(auditName, request, null, false, ex.getMessage(), System.currentTimeMillis() - start);
            throw ex;
        }
    }

    public <T> T renderPrompt(String promptName, Map<String, Object> request, Supplier<T> supplier) {
        authorizePrompt(promptName, request);
        long start = System.currentTimeMillis();
        String auditName = "prompt:" + promptName;
        try {
            T response = supplier.get();
            audit(auditName, request, response, true, null, System.currentTimeMillis() - start);
            return response;
        } catch (RuntimeException ex) {
            audit(auditName, request, null, false, ex.getMessage(), System.currentTimeMillis() - start);
            throw ex;
        }
    }

    public void authorize(String toolName) {
        authorize(toolName, Map.of());
    }

    public void authorize(String toolName, Map<String, Object> request) {
        if (!properties.isEnabled()) {
            return;
        }
        boolean highRisk = properties.getHighRiskTools().contains(toolName);
        if (!properties.getAllowlist().contains(toolName)) {
            boolean allowedNl2Sql = properties.isExposeNl2Sql()
                    && highRisk;
            if (!allowedNl2Sql) {
                throw new AiAuthorizationException("MCP tool is not allowlisted: " + toolName);
            }
        }
        if (properties.isRequireAdmin() || highRisk) {
            permissionService.requireOpsAccess();
        }
        if (highRisk && properties.isRequireHighRiskConfirmation()
                && !Boolean.TRUE.equals(request == null ? null : request.get("confirmed"))) {
            throw new AiAuthorizationException("MCP high-risk tool requires explicit confirmation: " + toolName);
        }
    }

    public void authorizeResource(String resourceUri, Map<String, Object> request) {
        authorizeBoundary("resource", resourceUri, properties.getResourceAllowlist(),
                properties.getHighRiskResources(), request);
    }

    public void authorizePrompt(String promptName, Map<String, Object> request) {
        authorizeBoundary("prompt", promptName, properties.getPromptAllowlist(),
                properties.getHighRiskPrompts(), request);
    }

    public Map<String, Object> governanceSnapshot() {
        List<Map<String, Object>> tools = properties.getAllowlist().stream()
                .sorted()
                .map(toolName -> toolDescriptor(toolName, true))
                .toList();
        List<Map<String, Object>> highRiskTools = properties.getHighRiskTools().stream()
                .sorted()
                .map(toolName -> toolDescriptor(toolName, properties.getAllowlist().contains(toolName)
                        || properties.isExposeNl2Sql()))
                .toList();
        List<Map<String, Object>> resources = properties.getResourceAllowlist().stream()
                .sorted()
                .map(resourceUri -> boundaryDescriptor("resource", resourceUri, true,
                        properties.getHighRiskResources().contains(resourceUri)))
                .toList();
        List<Map<String, Object>> prompts = properties.getPromptAllowlist().stream()
                .sorted()
                .map(promptName -> boundaryDescriptor("prompt", promptName, true,
                        properties.getHighRiskPrompts().contains(promptName)))
                .toList();
        List<Map<String, Object>> highRiskResources = properties.getHighRiskResources().stream()
                .sorted()
                .map(resourceUri -> boundaryDescriptor("resource", resourceUri,
                        properties.getResourceAllowlist().contains(resourceUri), true))
                .toList();
        List<Map<String, Object>> highRiskPrompts = properties.getHighRiskPrompts().stream()
                .sorted()
                .map(promptName -> boundaryDescriptor("prompt", promptName,
                        properties.getPromptAllowlist().contains(promptName), true))
                .toList();
        boolean governed = properties.isEnabled()
                && properties.isRequireAdmin()
                && properties.isRequireHighRiskConfirmation()
                && !properties.isExposeNl2Sql()
                && !properties.getAllowlist().isEmpty()
                && !properties.getResourceAllowlist().isEmpty()
                && !properties.getPromptAllowlist().isEmpty()
                && properties.getResourceAllowlist().containsAll(properties.getHighRiskResources())
                && properties.getPromptAllowlist().containsAll(properties.getHighRiskPrompts());
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("status", governed ? "PASS" : "REVIEW");
        snapshot.put("enabled", properties.isEnabled());
        snapshot.put("requireAdmin", properties.isRequireAdmin());
        snapshot.put("requireHighRiskConfirmation", properties.isRequireHighRiskConfirmation());
        snapshot.put("exposeNl2Sql", properties.isExposeNl2Sql());
        snapshot.put("allowlistSize", properties.getAllowlist().size());
        snapshot.put("resourceAllowlistSize", properties.getResourceAllowlist().size());
        snapshot.put("promptAllowlistSize", properties.getPromptAllowlist().size());
        snapshot.put("allowlistedTools", tools);
        snapshot.put("allowlistedResources", resources);
        snapshot.put("allowlistedPrompts", prompts);
        snapshot.put("highRiskTools", highRiskTools);
        snapshot.put("highRiskResources", highRiskResources);
        snapshot.put("highRiskPrompts", highRiskPrompts);
        snapshot.put("protocolSurfaces", List.of("tools", "resources", "prompts"));
        snapshot.put("policySummary", "MCP tools/resources/prompts are exposed only through explicit allowlists; high-risk surfaces require admin and confirmation.");
        return snapshot;
    }

    private void authorizeBoundary(String boundaryType,
                                   String name,
                                   java.util.Set<String> allowlist,
                                   java.util.Set<String> highRiskSet,
                                   Map<String, Object> request) {
        if (!properties.isEnabled()) {
            return;
        }
        boolean highRisk = highRiskSet.contains(name);
        if (!allowlist.contains(name)) {
            throw new AiAuthorizationException("MCP " + boundaryType + " is not allowlisted: " + name);
        }
        if (properties.isRequireAdmin() || highRisk) {
            permissionService.requireOpsAccess();
        }
        if (highRisk && properties.isRequireHighRiskConfirmation()
                && !Boolean.TRUE.equals(request == null ? null : request.get("confirmed"))) {
            throw new AiAuthorizationException("MCP high-risk " + boundaryType + " requires explicit confirmation: " + name);
        }
    }

    private Map<String, Object> toolDescriptor(String toolName, boolean exposed) {
        boolean highRisk = properties.getHighRiskTools().contains(toolName);
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("toolName", toolName);
        descriptor.put("exposed", exposed);
        descriptor.put("riskLevel", highRisk ? "HIGH" : "LOW");
        descriptor.put("requiresAdmin", properties.isRequireAdmin() || highRisk);
        descriptor.put("requiresConfirmation", highRisk && properties.isRequireHighRiskConfirmation());
        descriptor.put("scope", scopeOf(toolName));
        descriptor.put("reason", highRisk
                ? "can query protected operational or business data"
                : "read-only observability tool");
        return descriptor;
    }

    private Map<String, Object> boundaryDescriptor(String boundaryType,
                                                   String name,
                                                   boolean exposed,
                                                   boolean highRisk) {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("name", name);
        descriptor.put("surface", boundaryType);
        descriptor.put("exposed", exposed);
        descriptor.put("riskLevel", highRisk ? "HIGH" : "LOW");
        descriptor.put("requiresAdmin", properties.isRequireAdmin() || highRisk);
        descriptor.put("requiresConfirmation", highRisk && properties.isRequireHighRiskConfirmation());
        descriptor.put("scope", boundaryScope(boundaryType, name));
        descriptor.put("reason", highRisk
                ? "can expose run-state, prompt, or operational context"
                : "read-only MCP " + boundaryType + " boundary");
        return descriptor;
    }

    private String scopeOf(String toolName) {
        if (toolName.startsWith("log.")) {
            return "logs";
        }
        if (toolName.startsWith("metrics.")) {
            return "metrics";
        }
        if (toolName.startsWith("nl2sql.")) {
            return "data-agent";
        }
        return "custom";
    }

    private String boundaryScope(String boundaryType, String name) {
        if (name.startsWith("observability://")) {
            return "observability";
        }
        if (name.startsWith("assistant://")) {
            return "assistant-runtime";
        }
        if (name.startsWith("ops.")) {
            return "ops-prompt";
        }
        return boundaryType;
    }

    private void audit(String toolName,
                       Map<String, Object> request,
                       Object response,
                       boolean success,
                       String error,
                       long durationMs) {
        AiRequestContext context = AiRequestContextHolder.get();
        AiToolAudit audit = new AiToolAudit();
        audit.setRunId(context == null ? null : context.getRunId());
        audit.setChatId(context == null ? null : context.getConversationId());
        audit.setUserId(context == null || context.getUser() == null ? null : context.getUser().getUserId());
        audit.setToolName(toolName);
        audit.setToolType("mcp");
        audit.setRequestSummary(truncate(JSON.toJSONString(request == null ? Map.of() : request), 1000));
        Map<String, Object> responseSummary = new LinkedHashMap<>();
        responseSummary.put("durationMs", durationMs);
        responseSummary.put("preview", response);
        audit.setResponseSummary(truncate(JSON.toJSONString(responseSummary), 1000));
        audit.setSuccess(success);
        audit.setErrorMessage(truncate(error, 500));
        audit.setCreateTime(new Date());
        audit.setEditTime(new Date());
        audit.setStatus(1);
        toolAuditMapper.insert(audit);
    }

    private String truncate(String value, int maxLength) {
        if (!StringUtils.hasText(value) || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
