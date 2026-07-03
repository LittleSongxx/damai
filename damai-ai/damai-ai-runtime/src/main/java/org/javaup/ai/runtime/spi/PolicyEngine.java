package org.javaup.ai.runtime.spi;

import org.javaup.ai.runtime.contract.CapabilityDescriptor;
import org.javaup.ai.runtime.contract.PolicyDecision;

import java.util.Map;

public interface PolicyEngine {

    PolicyDecision decide(CapabilityDescriptor capability, Map<String, Object> input);
}
