package org.javaup.ai.assistant.skill.ops.nl2sql;

import org.javaup.ai.assistant.budget.TokenBudget;
import org.javaup.ai.assistant.budget.TokenBudgetManager;
import org.javaup.ai.assistant.tool.AssistantToolInvoker;
import org.javaup.ai.cache.CacheManager;
import org.javaup.ai.rag.prompt.PromptTemplateLoader;
import org.javaup.ai.service.Nl2SqlMultiTurnContextService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class Nl2SqlOrchestrator {

    private final ChatClient chatClient;
    private final Nl2SqlProperties properties;
    private final Nl2SqlSchemaService schemaService;
    private final Nl2SqlJsonParser jsonParser;
    private final Nl2SqlSafetyValidator safetyValidator;
    private final Nl2SqlExecutionService executionService;
    private final Nl2SqlErrorClassifier errorClassifier;
    private final AssistantToolInvoker toolInvoker;
    private final PromptTemplateLoader templateLoader;
    private final CacheManager cacheManager;
    private final Nl2SqlMultiTurnContextService multiTurnContextService;
    private final TokenBudgetManager tokenBudgetManager;

    public Nl2SqlOrchestrator(@Qualifier("unifiedOpsChatClient") ChatClient chatClient,
                              Nl2SqlProperties properties,
                              Nl2SqlSchemaService schemaService,
                              Nl2SqlJsonParser jsonParser,
                              Nl2SqlSafetyValidator safetyValidator,
                              Nl2SqlExecutionService executionService,
                              Nl2SqlErrorClassifier errorClassifier,
                              AssistantToolInvoker toolInvoker,
                              PromptTemplateLoader templateLoader,
                              CacheManager cacheManager,
                              Nl2SqlMultiTurnContextService multiTurnContextService,
                              TokenBudgetManager tokenBudgetManager) {
        this.chatClient = chatClient;
        this.properties = properties;
        this.schemaService = schemaService;
        this.jsonParser = jsonParser;
        this.safetyValidator = safetyValidator;
        this.executionService = executionService;
        this.errorClassifier = errorClassifier;
        this.toolInvoker = toolInvoker;
        this.templateLoader = templateLoader;
        this.cacheManager = cacheManager;
        this.multiTurnContextService = multiTurnContextService;
        this.tokenBudgetManager = tokenBudgetManager;
    }

    public Map<String, Object> answer(String runId, String question, String conversationKey) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("type", "nl2sql");
        evidence.put("question", question);
        evidence.put("enabled", properties.isEnabled());
        evidence.put("schemaCollection", properties.getSchemaCollection());
        if (!properties.isEnabled()) {
            evidence.put("status", "DISABLED");
            evidence.put("message", "NL2SQL 功能未启用");
            return finalizeResponse(evidence);
        }
        try {
            Nl2SqlSchemaContext schemaContext = toolInvoker.invoke(runId, "nl2sql.schemaRetrieve", "nl2sql",
                    Map.of("question", question), () -> schemaService.retrieve(question));
            evidence.put("schema", schemaContext.formattedSchema());
            evidence.put("schemaLinkingEvidence", Map.of(
                    "tables", schemaContext.tables().stream().map(Nl2SqlProperties.Table::getName).toList(),
                    "terms", schemaContext.terms().stream().map(Nl2SqlProperties.Term::getName).toList(),
                    "examples", schemaContext.examples().stream().map(Nl2SqlProperties.Example::getQuestion).toList()
            ));

            Nl2SqlGenerationResult generation = toolInvoker.invoke(runId, "nl2sql.sqlGenerate", "nl2sql",
                    Map.of("question", question, "tables", schemaContext.tables().stream().map(Nl2SqlProperties.Table::getName).toList()),
                    () -> generateSql(question, schemaContext, conversationKey, null, null));
            evidence.put("generation", generation);
            Map<String, Object> clarification = clarificationIfLowConfidence(generation);
            if (clarification != null) {
                evidence.putAll(clarification);
                return finalizeResponse(evidence);
            }
            // NL2SQL 独立上下文: 记录本轮问答，用于后续多轮指代消解
            if (generation.isNeedSql() && StringUtils.hasText(generation.getSql()) && conversationKey != null) {
                multiTurnContextService.recordTurn(conversationKey, question, generation.getSql(),
                        generation.getExplanation() != null ? generation.getExplanation() : "");
            }

            if (!generation.isNeedSql() || !StringUtils.hasText(generation.getSql())) {
                evidence.put("status", "NEED_CLARIFICATION");
                evidence.put("message", StringUtils.hasText(generation.getExplanation()) ? generation.getExplanation() : "问题不足以生成安全 SQL");
                evidence.put("safetyReport", Map.of(
                        "lowConfidenceBlocked", false,
                        "unsafeExecutionRejected", true,
                        "reason", "model declined to produce executable SQL"));
                return finalizeResponse(evidence);
            }

            Nl2SqlValidatedSql validatedSql = toolInvoker.invoke(runId, "nl2sql.astValidate", "nl2sql",
                    Map.of("sql", generation.getSql()), () -> safetyValidator.validate(generation.getSql()));
            evidence.put("validatedSql", validatedSql);
            evidence.put("sql", validatedSql.sql());
            evidence.put("safetyReport", Map.of(
                    "readonlyOnly", true,
                    "singleStatement", true,
                    "tables", validatedSql.tables(),
                    "maxRows", properties.getMaxRows(),
                    "queryTimeoutMs", properties.getQueryTimeoutMs(),
                    "joinsAllowed", properties.isAllowJoins(),
                    "subqueriesAllowed", properties.isAllowSubqueries(),
                    "cteAllowed", properties.isAllowCte(),
                    "setOperationsAllowed", properties.isAllowSetOperations(),
                    "windowFunctionsAllowed", properties.isAllowWindowFunctions()
            ));
            evidence.put("executionPlan", Map.of(
                    "mode", executionService.isConfigured() ? "READONLY_EXECUTION" : "SQL_ONLY",
                    "costGuard", properties.getCostGuard().isEnabled() ? "EXPLAIN_LIMIT_AND_TIMEOUT" : "LIMIT_AND_TIMEOUT",
                    "repairAttempts", properties.getRepairAttempts()
            ));

            String cachedResult = cacheManager.getNl2sqlResult(validatedSql.sql());
            if (cachedResult != null) {
                Nl2SqlExecutionResult cachedExecution = com.alibaba.fastjson2.JSON.parseObject(cachedResult, Nl2SqlExecutionResult.class);
                evidence.put("execution", cachedExecution);
                evidence.put("costGuard", cachedExecution.costGuard() == null ? Map.of() : cachedExecution.costGuard());
                evidence.put("resultPreview", cachedExecution.rows());
                evidence.put("maskedColumns", cachedExecution.columns().stream()
                        .filter(this::isSensitiveColumn)
                        .toList());
                evidence.put("status", "COMPLETED");
                evidence.put("cacheHit", true);
                return finalizeResponse(evidence);
            }

            Nl2SqlExecutionResult execution = executeWithRepair(runId, question, conversationKey, schemaContext, evidence, validatedSql);
            evidence.put("execution", execution);
            evidence.put("costGuard", execution.costGuard() == null ? Map.of() : execution.costGuard());
            evidence.put("resultPreview", execution.rows());
            evidence.put("maskedColumns", execution.columns().stream()
                    .filter(this::isSensitiveColumn)
                    .toList());
            evidence.put("status", execution.skipped() ? "SQL_READY" : "COMPLETED");
            if (!execution.skipped()) {
                cacheManager.putNl2sqlResult(validatedSql.sql(), com.alibaba.fastjson2.JSON.toJSONString(execution));
            }
            return finalizeResponse(evidence);
        } catch (Nl2SqlException ex) {
            evidence.put("status", "FAILED");
            evidence.put("message", ex.getMessage());
            applyFailureSafetyReport(evidence, ex.getMessage());
            return finalizeResponse(evidence);
        } catch (RuntimeException ex) {
            evidence.put("status", "FAILED");
            evidence.put("message", "NL2SQL 执行失败: " + ex.getMessage());
            applyFailureSafetyReport(evidence, ex.getMessage());
            return finalizeResponse(evidence);
        }
    }

    Map<String, Object> clarificationIfLowConfidence(Nl2SqlGenerationResult generation) {
        if (generation == null || !generation.isNeedSql()) {
            return null;
        }
        double confidence = generation.getConfidence() == null ? 0D : generation.getConfidence();
        if (confidence >= properties.getMinSqlConfidence()) {
            return null;
        }
        Map<String, Object> clarification = new LinkedHashMap<>();
        clarification.put("status", "NEED_CLARIFICATION");
        clarification.put("message", StringUtils.hasText(generation.getExplanation())
                ? generation.getExplanation()
                : "NL2SQL 置信度低于执行阈值，请补充时间范围、统计口径或筛选条件。");
        clarification.put("safetyReport", Map.of(
                "lowConfidenceBlocked", true,
                "confidence", confidence,
                "minSqlConfidence", properties.getMinSqlConfidence(),
                "unsafeExecutionRejected", true));
        clarification.put("evidence", Map.of(
                "tables", generation.getTables() == null ? java.util.List.of() : generation.getTables(),
                "assumptions", generation.getAssumptions() == null ? java.util.List.of() : generation.getAssumptions(),
                "schemaLinkingRequired", true));
        return finalizeResponse(clarification);
    }

    Map<String, Object> finalizeResponse(Map<String, Object> response) {
        response.putIfAbsent("status", "UNKNOWN");
        response.putIfAbsent("sql", safeTopLevelSql(response));
        response.putIfAbsent("evidence", contractEvidence(response));
        response.put("safetyReport", mergedSafetyReport(response));
        response.put("executionPlan", executionPlan(response));
        response.putIfAbsent("resultPreview", resultPreview(response));
        response.putIfAbsent("maskedColumns", maskedColumns(response));
        response.putIfAbsent("repairTrace", repairTrace(response));
        return response;
    }

    private String safeTopLevelSql(Map<String, Object> response) {
        Object existing = response.get("sql");
        if (existing != null && StringUtils.hasText(String.valueOf(existing))) {
            return String.valueOf(existing);
        }
        Object validated = response.get("validatedSql");
        if (validated instanceof Nl2SqlValidatedSql validatedSql) {
            return validatedSql.sql();
        }
        Object repaired = response.get("repairedSql");
        if (repaired instanceof Nl2SqlValidatedSql repairedSql) {
            return repairedSql.sql();
        }
        return "";
    }

    private Map<String, Object> contractEvidence(Map<String, Object> response) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("schemaLinkingEvidence", response.getOrDefault("schemaLinkingEvidence", Map.of()));
        Object generation = response.get("generation");
        if (generation instanceof Nl2SqlGenerationResult result) {
            evidence.put("generation", Map.of(
                    "needSql", result.isNeedSql(),
                    "tables", result.getTables() == null ? java.util.List.of() : result.getTables(),
                    "confidence", result.getConfidence() == null ? 0D : result.getConfidence(),
                    "assumptions", result.getAssumptions() == null ? java.util.List.of() : result.getAssumptions(),
                    "explanation", result.getExplanation() == null ? "" : result.getExplanation()));
        }
        Object validated = response.get("validatedSql");
        if (validated instanceof Nl2SqlValidatedSql validatedSql) {
            evidence.put("validatedTables", validatedSql.tables());
        }
        return evidence;
    }

    private Map<String, Object> mergedSafetyReport(Map<String, Object> response) {
        Map<String, Object> safety = new LinkedHashMap<>(mapValue(response.get("safetyReport")));
        safety.putIfAbsent("readonlyOnly", true);
        safety.putIfAbsent("singleStatement", true);
        safety.putIfAbsent("unsafeExecutionRejected", false);
        safety.putIfAbsent("lowConfidenceBlocked", false);
        safety.putIfAbsent("maxRows", properties.getMaxRows());
        safety.putIfAbsent("queryTimeoutMs", properties.getQueryTimeoutMs());
        safety.putIfAbsent("explainTimeoutMs", properties.getCostGuard().getExplainTimeoutMs());
        safety.putIfAbsent("costGuardEnabled", properties.getCostGuard().isEnabled());
        safety.putIfAbsent("joinsAllowed", properties.isAllowJoins());
        safety.putIfAbsent("subqueriesAllowed", properties.isAllowSubqueries());
        safety.putIfAbsent("cteAllowed", properties.isAllowCte());
        safety.putIfAbsent("setOperationsAllowed", properties.isAllowSetOperations());
        safety.putIfAbsent("windowFunctionsAllowed", properties.isAllowWindowFunctions());
        Object costGuard = response.get("costGuard");
        if (costGuard instanceof Map<?, ?> costMap) {
            safety.put("costGuardStatus", valueOrDefault(costMap.get("status"), ""));
            safety.put("costGuardExplainMode", valueOrDefault(costMap.get("explainMode"), ""));
            safety.put("estimatedRows", numericOrDefault(costMap.get("estimatedRows"), -1L));
            safety.put("queryCost", numericOrDefault(costMap.get("queryCost"), -1D));
            safety.put("maxEstimatedRows", properties.getCostGuard().getMaxEstimatedRows());
            safety.put("maxQueryCost", properties.getCostGuard().getMaxQueryCost());
        }
        Object execution = response.get("execution");
        if (execution instanceof Nl2SqlExecutionResult result) {
            safety.put("rowCount", result.rowCount());
            safety.put("truncated", result.truncated());
            safety.put("executionSkipped", result.skipped());
            safety.put("durationMs", result.durationMs());
        }
        if (!safety.containsKey("tables")) {
            Object validated = response.get("validatedSql");
            if (validated instanceof Nl2SqlValidatedSql validatedSql) {
                safety.put("tables", validatedSql.tables());
            } else {
                safety.put("tables", java.util.List.of());
            }
        }
        return safety;
    }

    private Map<String, Object> executionPlan(Map<String, Object> response) {
        Map<String, Object> existing = new LinkedHashMap<>(mapValue(response.get("executionPlan")));
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("mode", executionService != null && executionService.isConfigured() ? "READONLY_EXECUTION" : "SQL_ONLY");
        plan.put("costGuard", properties.getCostGuard().isEnabled() ? "EXPLAIN_LIMIT_AND_TIMEOUT" : "LIMIT_AND_TIMEOUT");
        plan.put("repairAttempts", properties.getRepairAttempts());
        plan.put("maxRows", properties.getMaxRows());
        plan.put("queryTimeoutMs", properties.getQueryTimeoutMs());
        plan.put("explainTimeoutMs", properties.getCostGuard().getExplainTimeoutMs());
        Object costGuard = response.get("costGuard");
        if (costGuard instanceof Map<?, ?> costMap) {
            plan.put("costGuardReport", new LinkedHashMap<>(mapValue(costMap)));
            plan.put("estimatedRows", numericOrDefault(costMap.get("estimatedRows"), -1L));
            plan.put("queryCost", numericOrDefault(costMap.get("queryCost"), -1D));
        }
        Object execution = response.get("execution");
        if (execution instanceof Nl2SqlExecutionResult result) {
            plan.put("executed", !result.skipped());
            plan.put("skipped", result.skipped());
            plan.put("skipReason", result.skipReason() == null ? "" : result.skipReason());
            plan.put("rowCount", result.rowCount());
            plan.put("truncated", result.truncated());
            plan.put("durationMs", result.durationMs());
        } else {
            plan.put("executed", false);
            plan.put("skipped", false);
        }
        plan.putAll(existing);
        return plan;
    }

    private Object resultPreview(Map<String, Object> response) {
        Object execution = response.get("execution");
        if (execution instanceof Nl2SqlExecutionResult result) {
            return result.rows();
        }
        return java.util.List.of();
    }

    private List<String> maskedColumns(Map<String, Object> response) {
        Object execution = response.get("execution");
        if (execution instanceof Nl2SqlExecutionResult result) {
            return result.columns().stream()
                    .filter(this::isSensitiveColumn)
                    .toList();
        }
        return java.util.List.of();
    }

    private Map<String, Object> repairTrace(Map<String, Object> response) {
        Map<String, Object> trace = new LinkedHashMap<>();
        if (response.containsKey("repairGeneration")) {
            trace.put("repairGeneration", response.get("repairGeneration"));
        }
        if (response.containsKey("repairedSql")) {
            trace.put("repairedSql", response.get("repairedSql"));
        }
        trace.putIfAbsent("attempted", response.containsKey("repairGeneration") || response.containsKey("repairedSql"));
        return trace;
    }

    private void applyFailureSafetyReport(Map<String, Object> response, String message) {
        Map<String, Object> safety = new LinkedHashMap<>(mapValue(response.get("safetyReport")));
        boolean unsafeRejected = isUnsafeRejection(message);
        safety.put("unsafeExecutionRejected", unsafeRejected);
        safety.put("rejected", true);
        safety.put("rejectionStage", unsafeRejected ? "SAFETY_OR_COST_GUARD" : "EXECUTION");
        safety.put("rejectionReason", message == null ? "" : message);
        response.put("safetyReport", safety);
    }

    private boolean isUnsafeRejection(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String normalized = message.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("禁止")
                || normalized.contains("白名单")
                || normalized.contains("只允许")
                || normalized.contains("安全")
                || normalized.contains("敏感")
                || normalized.contains("单条 sql")
                || normalized.contains("readonly")
                || normalized.contains("估算扫描行数过高")
                || normalized.contains("估算成本过高")
                || normalized.contains("explain 成本检查失败");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return Map.of();
    }

    private Object valueOrDefault(Object value, Object defaultValue) {
        return value == null ? defaultValue : value;
    }

    private Object numericOrDefault(Object value, Number defaultValue) {
        if (value instanceof Number) {
            return value;
        }
        if (value == null) {
            return defaultValue;
        }
        try {
            String raw = String.valueOf(value);
            return raw.contains(".") ? Double.parseDouble(raw) : Long.parseLong(raw);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private boolean isSensitiveColumn(String column) {
        if (!StringUtils.hasText(column)) {
            return false;
        }
        String normalized = column.toLowerCase(java.util.Locale.ROOT);
        return properties.getSensitiveColumns().stream()
                .map(value -> value.toLowerCase(java.util.Locale.ROOT))
                .anyMatch(normalized::contains);
    }

    private Nl2SqlExecutionResult executeWithRepair(String runId,
                                                    String question,
                                                    String conversationKey,
                                                    Nl2SqlSchemaContext schemaContext,
                                                    Map<String, Object> evidence,
                                                    Nl2SqlValidatedSql validatedSql) {
        try {
            return toolInvoker.invoke(runId, "nl2sql.executeReadonly", "nl2sql",
                    Map.of("sql", validatedSql.sql()), () -> executionService.execute(validatedSql.sql()));
        } catch (Nl2SqlException ex) {
            if (!executionService.isConfigured() || properties.getRepairAttempts() <= 0) {
                throw ex;
            }
            Nl2SqlGenerationResult repair = toolInvoker.invoke(runId, "nl2sql.sqlRepair", "nl2sql",
                    Map.of("question", question, "sql", validatedSql.sql(), "error", ex.getMessage()),
                    () -> generateSql(question, schemaContext, conversationKey, validatedSql.sql(), ex.getMessage()));
            evidence.put("repairGeneration", repair);
            if (!repair.isNeedSql() || !StringUtils.hasText(repair.getSql())) {
                throw ex;
            }
            Nl2SqlValidatedSql repairedSql = toolInvoker.invoke(runId, "nl2sql.repairAstValidate", "nl2sql",
                    Map.of("sql", repair.getSql()), () -> safetyValidator.validate(repair.getSql()));
            evidence.put("repairedSql", repairedSql);
            return toolInvoker.invoke(runId, "nl2sql.executeReadonlyRepaired", "nl2sql",
                    Map.of("sql", repairedSql.sql()), () -> executionService.execute(repairedSql.sql()));
        }
    }

    private Nl2SqlGenerationResult generateSql(String question,
                                               Nl2SqlSchemaContext schemaContext,
                                               String conversationKey,
                                               String previousSql,
                                               String previousError) {
        Nl2SqlErrorClassifier.ErrorCategory errorCategory = errorClassifier.classify(previousError);
        String repairGuidance = errorClassifier.buildRepairGuidance(errorCategory);

        // NL2SQL 独立上下文窗口: 注入多轮对话历史前缀，不与其他 Skill 共享 ChatMemory
        StringBuilder fullPrompt = new StringBuilder();
        if (conversationKey != null) {
            String contextPrefix = multiTurnContextService.buildContextPrefix(conversationKey);
            if (!contextPrefix.isEmpty()) {
                fullPrompt.append(contextPrefix);
            }
        }
        fullPrompt.append(buildGenerationPrompt(question, schemaContext, previousSql, previousError, repairGuidance));
        String prompt = fullPrompt.toString();

        // Token 预算跟踪: 独立统计 NL2SQL 提示各组成部分
        TokenBudget budget = tokenBudgetManager.createBudget("qwen3.6-plus");
        budget.recordUsage("SYSTEM_PROMPT", tokenBudgetManager.estimateTokens(schemaContext.formattedSchema()));
        tokenBudgetManager.verifyBudget(budget);

        ChatClient.ChatClientRequestSpec request = chatClient.prompt().user(prompt);
        if (StringUtils.hasText(conversationKey)) {
            request.advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationKey));
        }
        String raw = request.call().content();
        return jsonParser.parseGenerationResult(raw);
    }

    private String buildGenerationPrompt(String question,
                                         Nl2SqlSchemaContext schemaContext,
                                         String previousSql,
                                         String previousError,
                                         String repairGuidance) {
        StringBuilder terms = new StringBuilder();
        schemaContext.terms().forEach(term -> terms.append("- ")
                .append(term.getName())
                .append(": ")
                .append(term.getDescription())
                .append('\n'));
        StringBuilder examples = new StringBuilder();
        schemaContext.examples().forEach(example -> examples.append("- Q: ")
                .append(example.getQuestion())
                .append("\n  SQL: ")
                .append(example.getSql())
                .append('\n'));
        String repairBlock = "";
        if (StringUtils.hasText(previousSql) || StringUtils.hasText(previousError)) {
            repairBlock = """

                    上一次 SQL：
                    %s

                    上一次错误：
                    %s

                    修复指导：
                    %s
                    """.formatted(previousSql, previousError, repairGuidance);
        }
        if (templateLoader != null && templateLoader.hasTemplate("nl2sql-generate.st")) {
            return templateLoader.render("nl2sql-generate.st", Map.of(
                    "schema_block", schemaContext.formattedSchema(),
                    "terms_block", terms.toString(),
                    "examples_block", examples.toString(),
                    "repair_block", repairBlock,
                    "user_question", question
            ));
        }
        return """
                你是大麦运维问数助手，负责把自然语言问题转换成安全的 MySQL SELECT 查询。
                只能使用给定 Schema 中的表和字段；如果信息不足以安全生成 SQL，needSql=false。
                禁止查询敏感字段，禁止 DDL/DML/DCL，禁止多语句，禁止 select *。
                如果 confidence < 0.5，设置 needSql=false 并给出 explanation 说明不确定原因。
                必须返回纯 JSON，不要 Markdown，不要解释性前后缀。

                JSON 结构：
                {
                  "needSql": true,
                  "sql": "select ...",
                  "tables": ["表名"],
                  "explanation": "简短说明",
                  "chartType": "table|line|bar|pie|number",
                  "confidence": 0.0,
                  "assumptions": ["必要假设"]
                }

                Schema：
                %s

                业务术语：
                %s

                示例：
                %s
                %s
                用户问题：
                %s
                """.formatted(schemaContext.formattedSchema(), terms, examples, repairBlock, question);
    }
}
