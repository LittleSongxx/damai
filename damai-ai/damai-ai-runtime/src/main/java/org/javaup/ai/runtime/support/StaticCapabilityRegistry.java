package org.javaup.ai.runtime.support;

import org.javaup.ai.runtime.contract.CapabilityDescriptor;
import org.javaup.ai.runtime.spi.CapabilityProvider;
import org.javaup.ai.runtime.spi.CapabilityRegistry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

public class StaticCapabilityRegistry implements CapabilityRegistry {

    private final Map<String, CapabilityDescriptor> capabilities;

    public StaticCapabilityRegistry(Collection<CapabilityProvider> providers) {
        this.capabilities = providers.stream()
                .map(CapabilityProvider::capabilities)
                .flatMap(Collection::stream)
                .collect(Collectors.toUnmodifiableMap(
                        CapabilityDescriptor::getCapabilityId,
                        Function.identity(),
                        (left, right) -> right));
    }

    @Override
    public Collection<CapabilityDescriptor> list() {
        return new ArrayList<>(capabilities.values());
    }

    @Override
    public Optional<CapabilityDescriptor> find(String capabilityId) {
        return Optional.ofNullable(capabilities.get(capabilityId));
    }
}
