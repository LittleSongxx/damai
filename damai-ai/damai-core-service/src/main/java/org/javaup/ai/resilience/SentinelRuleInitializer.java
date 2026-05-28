package org.javaup.ai.resilience;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.config.SentinelProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class SentinelRuleInitializer {

    private final SentinelProperties properties;

    @PostConstruct
    public void init() {
        List<DegradeRule> degradeRules = new ArrayList<>();
        List<FlowRule> flowRules = new ArrayList<>();
        addRules(properties.getLlm(), degradeRules, flowRules);
        addRules(properties.getQdrant(), degradeRules, flowRules);
        addRules(properties.getEs(), degradeRules, flowRules);
        addRules(properties.getWebSearch(), degradeRules, flowRules);
        addRules(properties.getUserService(), degradeRules, flowRules);
        DegradeRuleManager.loadRules(degradeRules);
        FlowRuleManager.loadRules(flowRules);
    }

    private void addRules(SentinelProperties.ResourceRule rule,
                          List<DegradeRule> degradeRules,
                          List<FlowRule> flowRules) {
        if (rule == null || !rule.isEnabled() || !StringUtils.hasText(rule.getResourceName())) {
            return;
        }
        SentinelProperties.Degrade degrade = rule.getDegrade();
        if (degrade != null && degrade.isEnabled()) {
            degradeRules.add(buildDegradeRule(rule.getResourceName(), degrade));
        }
        SentinelProperties.Flow flow = rule.getFlow();
        if (flow != null && flow.isEnabled()) {
            flowRules.add(buildFlowRule(rule.getResourceName(), flow));
        }
    }

    private DegradeRule buildDegradeRule(String resourceName, SentinelProperties.Degrade config) {
        DegradeRule rule = new DegradeRule(resourceName);
        rule.setGrade(degradeGrade(config.getGrade()));
        rule.setCount(degradeCount(config));
        rule.setSlowRatioThreshold(Math.max(0.0, Math.min(1.0, config.getSlowRatioThreshold())));
        rule.setMinRequestAmount(Math.max(1, config.getMinRequestAmount()));
        rule.setStatIntervalMs(Math.max(1000, config.getStatIntervalMs()));
        rule.setTimeWindow(Math.max(1, config.getTimeWindowSeconds()));
        return rule;
    }

    private int degradeGrade(String grade) {
        if ("slow_request_ratio".equalsIgnoreCase(grade)) {
            return RuleConstant.DEGRADE_GRADE_RT;
        }
        if ("exception_count".equalsIgnoreCase(grade)) {
            return RuleConstant.DEGRADE_GRADE_EXCEPTION_COUNT;
        }
        return RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO;
    }

    private double degradeCount(SentinelProperties.Degrade config) {
        int grade = degradeGrade(config.getGrade());
        if (grade == RuleConstant.DEGRADE_GRADE_RT) {
            return Math.max(1, config.getSlowRequestThresholdMs());
        }
        if (grade == RuleConstant.DEGRADE_GRADE_EXCEPTION_COUNT) {
            return Math.max(1, config.getExceptionCount());
        }
        return Math.max(0.0, Math.min(1.0, config.getExceptionRatio()));
    }

    private FlowRule buildFlowRule(String resourceName, SentinelProperties.Flow config) {
        FlowRule rule = new FlowRule(resourceName);
        rule.setGrade(flowGrade(config.getGrade()));
        rule.setCount(Math.max(1.0, config.getCount()));
        return rule;
    }

    private int flowGrade(String grade) {
        if ("thread".equalsIgnoreCase(grade)) {
            return RuleConstant.FLOW_GRADE_THREAD;
        }
        return RuleConstant.FLOW_GRADE_QPS;
    }
}
