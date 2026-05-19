package org.javaup.ai.assistant.skill.ops.nl2sql;

import org.javaup.ai.assistant.tool.AssistantToolInvoker;
import org.javaup.ai.rag.prompt.PromptTemplateLoader;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
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

    public Nl2SqlOrchestrator(@Qualifier("unifiedOpsChatClient") ChatClient chatClient,
                              Nl2SqlProperties properties,
                              Nl2SqlSchemaService schemaService,
                              Nl2SqlJsonParser jsonParser,
                              Nl2SqlSafetyValidator safetyValidator,
                              Nl2SqlExecutionService executionService,
                              Nl2SqlErrorClassifier errorClassifier,
                              AssistantToolInvoker toolInvoker,
                              PromptTemplateLoader templateLoader) {
        this.chatClient = chatClient;
        this.properties = properties;
        this.schemaService = schemaService;
        this.jsonParser = jsonParser;
        this.safetyValidator = safetyValidator;
        this.executionService = executionService;
        this.errorClassifier = errorClassifier;
        this.toolInvoker = toolInvoker;
        this.templateLoader = templateLoader;
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
            return evidence;
        }
        try {
            Nl2SqlSchemaContext schemaContext = toolInvoker.invoke(runId, "nl2sql.schemaRetrieve", "nl2sql",
                    Map.of("question", question), () -> schemaService.retrieve(question));
            evidence.put("schema", schemaContext.formattedSchema());

            Nl2SqlGenerationResult generation = toolInvoker.invoke(runId, "nl2sql.sqlGenerate", "nl2sql",
                    Map.of("question", question, "tables", schemaContext.tables().stream().map(Nl2SqlProperties.Table::getName).toList()),
                    () -> generateSql(question, schemaContext, conversationKey, null, null));
            evidence.put("generation", generation);

            if (!generation.isNeedSql() || !StringUtils.hasText(generation.getSql())) {
                evidence.put("status", "NEED_CLARIFICATION");
                evidence.put("message", StringUtils.hasText(generation.getExplanation()) ? generation.getExplanation() : "问题不足以生成安全 SQL");
                return evidence;
            }

            Nl2SqlValidatedSql validatedSql = toolInvoker.invoke(runId, "nl2sql.astValidate", "nl2sql",
                    Map.of("sql", generation.getSql()), () -> safetyValidator.validate(generation.getSql()));
            evidence.put("validatedSql", validatedSql);

            Nl2SqlExecutionResult execution = executeWithRepair(runId, question, conversationKey, schemaContext, evidence, validatedSql);
            evidence.put("execution", execution);
            evidence.put("status", execution.skipped() ? "SQL_READY" : "COMPLETED");
            return evidence;
        } catch (Nl2SqlException ex) {
            evidence.put("status", "FAILED");
            evidence.put("message", ex.getMessage());
            return evidence;
        } catch (RuntimeException ex) {
            evidence.put("status", "FAILED");
            evidence.put("message", "NL2SQL 执行失败: " + ex.getMessage());
            return evidence;
        }
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
        String prompt = buildGenerationPrompt(question, schemaContext, previousSql, previousError, repairGuidance);
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
