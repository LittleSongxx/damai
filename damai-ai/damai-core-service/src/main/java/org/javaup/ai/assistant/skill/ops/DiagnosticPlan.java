package org.javaup.ai.assistant.skill.ops;

import java.util.ArrayList;
import java.util.List;

/**
 * AIOps 诊断计划 —— 遵循 LangGraph chain 模式与 CrewAI 层次化任务委派设计。
 *
 * <p>将运维诊断问题分解为有序的诊断步骤链: 概览 → 下钻 → 关联 → 结论。
 * 每个步骤指定要调用的工具、参数和预期输出，支持条件分支。
 */
public class DiagnosticPlan {

    private final String originalQuery;
    private final List<Step> steps = new ArrayList<>();
    private int currentStepIndex;
    private DiagnosticConclusion conclusion;

    public DiagnosticPlan(String originalQuery) {
        this.originalQuery = originalQuery;
    }

    public void addStep(Step step) {
        steps.add(step);
    }

    public Step currentStep() {
        if (currentStepIndex >= steps.size()) {
            return null;
        }
        return steps.get(currentStepIndex);
    }

    public void advance() {
        currentStepIndex++;
    }

    public boolean hasMoreSteps() {
        return currentStepIndex < steps.size();
    }

    public void conclude(DiagnosticConclusion conclusion) {
        this.conclusion = conclusion;
    }

    public String getOriginalQuery() {
        return originalQuery;
    }

    public List<Step> getSteps() {
        return List.copyOf(steps);
    }

    public int getCurrentStepIndex() {
        return currentStepIndex;
    }

    public int getTotalSteps() {
        return steps.size();
    }

    public DiagnosticConclusion getConclusion() {
        return conclusion;
    }

    /**
     * 诊断链中的单个步骤。
     */
    public record Step(
            String phase,
            String toolName,
            String description,
            List<String> serviceTargets,
            boolean conditional,
            String conditionDescription
    ) {
        public Step(String phase, String toolName, String description) {
            this(phase, toolName, description, List.of(), false, null);
        }

        public Step(String phase, String toolName, String description, List<String> serviceTargets) {
            this(phase, toolName, description, serviceTargets, false, null);
        }
    }

    /**
     * 诊断结论，包含根因分析和建议。
     */
    public record DiagnosticConclusion(
            String rootCauseSummary,
            String affectedService,
            String severity,
            List<String> recommendations,
            List<String> evidenceTraceIds
    ) {
        public DiagnosticConclusion(String rootCauseSummary, String affectedService, String severity,
                                     List<String> recommendations) {
            this(rootCauseSummary, affectedService, severity, recommendations, List.of());
        }
    }
}
