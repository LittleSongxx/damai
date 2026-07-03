package org.javaup.ai.runtime.spi;

import org.javaup.ai.runtime.contract.CapabilityDescriptor;

import java.util.Collection;

public interface CapabilityProvider {

    Collection<CapabilityDescriptor> capabilities();
}
