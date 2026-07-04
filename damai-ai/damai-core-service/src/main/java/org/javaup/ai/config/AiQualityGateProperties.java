package org.javaup.ai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "damai.ai.quality-gate")
public class AiQualityGateProperties {

    private Nl2sql nl2sql = new Nl2sql();
    private CustomerService customerService = new CustomerService();
    private AiOps aiOps = new AiOps();
    private RedTeam redTeam = new RedTeam();
    private Coverage coverage = new Coverage();

    @Data
    public static class Nl2sql {
        private double sqlValidityRate = 0.90D;
        private double executionAccuracy = 0.80D;
        private double schemaLinkRecall = 0.70D;
    }

    @Data
    public static class CustomerService {
        private long minEventsForStrictGate = 10L;
        private double minQuickAnswerHitRate = 0.25D;
        private double maxWorkItemRate = 0.35D;
        private double maxNegativeSentimentRate = 0.30D;
        private double minSatisfactionRate = 0.70D;
        private double requiredP95QuickAnswerLatencyMs = 300D;
    }

    @Data
    public static class AiOps {
        private long minHealthyProviders = 4L;
        private String requiredSignalType = "businessEvents";
    }

    @Data
    public static class RedTeam {
        private String status = "PASS";
        private String testClass = "RedTeamRegressionTest";
    }

    @Data
    public static class Coverage {
        private List<String> domains = List.of(
                "auth",
                "run-graph",
                "rag-evalops",
                "rag-ingestion-quality",
                "nl2sql-safety",
                "mcp-governance",
                "mcp-boundary",
                "assistant-eval-control-plane",
                "customer-service-experience",
                "aiops-rca",
                "prompt-governance",
                "prompt-release-plan",
                "red-team");
        private List<String> backendTestClasses = List.of(
                "AiAuthenticationInterceptorTest",
                "AssistantRunGraphServiceTest",
                "McpToolGovernanceServiceTest",
                "McpBoundaryServiceTest",
                "AiQualityGateServiceTest",
                "AssistantEvalRunServiceTest",
                "PromptVersionServiceTest",
                "CustomerHotQuestionServiceTest",
                "CustomerServiceMetricsServiceTest",
                "RagRetrievalFacadeTest",
                "KnowledgeRetrievalOrchestratorTest",
                "IngestionQualityServiceTest",
                "RagIngestionConsumerTest",
                "DocumentLifecycleServiceTest",
                "RedTeamRegressionTest",
                "AssistantSkillSchemaValidatorTest",
                "Nl2SqlSafetyValidatorTest",
                "Nl2SqlExecutionServiceTest",
                "Nl2SqlOrchestratorPolicyTest",
                "Nl2SqlEvalServiceTest",
                "RagEvalServiceTest",
                "RagEvalOpsServicesTest",
                "OpsRcaEvidenceServiceTest");
        private List<String> frontendTestFiles = List.of(
                "AssistantHub.spec.js",
                "PromptGovernance.spec.js",
                "useAssistantRuntime.spec.js",
                "api.spec.js");
        private int minimumRagGoldCases = 50;
        private int minimumNl2sqlGoldCases = 50;
        private int minimumRedTeamCases = 30;
    }
}
