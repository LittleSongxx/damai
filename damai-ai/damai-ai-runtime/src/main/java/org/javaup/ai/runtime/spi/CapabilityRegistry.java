package org.javaup.ai.runtime.spi;

import org.javaup.ai.runtime.contract.CapabilityDescriptor;

import java.util.Collection;
import java.util.Optional;

public interface CapabilityRegistry {

    Collection<CapabilityDescriptor> list();

    Optional<CapabilityDescriptor> find(String capabilityId);

    default CapabilityDescriptor getRequired(String capabilityId) {
        return find(capabilityId)
                .orElseThrow(() -> new IllegalArgumentException("Capability not found: " + capabilityId));
    }
}
