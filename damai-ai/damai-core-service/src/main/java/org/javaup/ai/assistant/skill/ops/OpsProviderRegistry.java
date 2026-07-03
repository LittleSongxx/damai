package org.javaup.ai.assistant.skill.ops;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class OpsProviderRegistry {

    public static final List<String> EXPECTED_PROVIDERS = List.of(
            "logs", "metrics", "traces", "alerts", "changes", "topology", "runbooks", "businessEvents");

    private List<OpsEvidenceProvider> providers = List.of();

    @Autowired(required = false)
    public void setProviders(List<OpsEvidenceProvider> providers) {
        this.providers = providers == null ? List.of() : List.copyOf(providers);
    }

    public Map<String, Object> collect(OpsRcaRequest request, Instant start, Instant end) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (OpsEvidenceProvider provider : providers) {
            try {
                boolean available = provider.available();
                result.put(provider.signalType(), Map.of(
                        "provider", provider.name(),
                        "signalType", provider.signalType(),
                        "available", available,
                        "payload", provider.collect(request, start, end)));
            } catch (Exception ex) {
                result.put(provider.signalType(), Map.of(
                        "provider", provider.name(),
                        "signalType", provider.signalType(),
                        "available", false,
                        "error", ex.getMessage() == null ? "" : ex.getMessage()));
            }
        }
        return result;
    }

    public Map<String, Object> coverage(Map<String, Object> providerEvidence) {
        Set<String> available = new LinkedHashSet<>();
        Set<String> unavailable = new LinkedHashSet<>();
        Set<String> evidenceFound = new LinkedHashSet<>();
        Set<String> evidenceRelevant = new LinkedHashSet<>();
        providerEvidence.forEach((signalType, raw) -> {
            if (raw instanceof Map<?, ?> wrapper && Boolean.TRUE.equals(wrapper.get("available"))
                    && !wrapper.containsKey("error")) {
                available.add(signalType);
                Object payload = wrapper.get("payload");
                if (hasEvidence(payload)) {
                    evidenceFound.add(signalType);
                }
                if (isRelevant(payload)) {
                    evidenceRelevant.add(signalType);
                }
            } else {
                unavailable.add(signalType);
            }
        });
        List<String> missing = EXPECTED_PROVIDERS.stream()
                .filter(provider -> !available.contains(provider))
                .toList();
        double ratio = EXPECTED_PROVIDERS.isEmpty()
                ? 1D
                : Math.round((EXPECTED_PROVIDERS.size() - missing.size()) * 1000D / EXPECTED_PROVIDERS.size()) / 1000D;
        double relevanceRatio = EXPECTED_PROVIDERS.isEmpty()
                ? 1D
                : Math.round(evidenceRelevant.size() * 1000D / EXPECTED_PROVIDERS.size()) / 1000D;
        return Map.of(
                "expectedProviders", EXPECTED_PROVIDERS,
                "availableProviders", available.stream().sorted().toList(),
                "unavailableProviders", unavailable.stream().sorted().toList(),
                "evidenceFoundProviders", evidenceFound.stream().sorted().toList(),
                "evidenceRelevantProviders", evidenceRelevant.stream().sorted().toList(),
                "missingProviders", missing,
                "coverageRatio", ratio,
                "relevanceRatio", relevanceRatio,
                "humanReviewRequired", !missing.isEmpty() || evidenceRelevant.size() < 2);
    }

    private boolean isRelevant(Object payload) {
        if (!(payload instanceof Map<?, ?> map)) {
            return false;
        }
        if (Boolean.TRUE.equals(map.get("relevant"))) {
            return true;
        }
        Object items = map.get("items");
        if (items instanceof List<?> list) {
            return !list.isEmpty();
        }
        Object linked = map.get("linkedSignals");
        if (linked instanceof List<?> list) {
            return !list.isEmpty();
        }
        return hasEvidence(payload) && !Boolean.FALSE.equals(map.get("configured"));
    }

    private boolean hasEvidence(Object payload) {
        if (!(payload instanceof Map<?, ?> map) || map.isEmpty()) {
            return false;
        }
        if (Boolean.FALSE.equals(map.get("configured"))) {
            return false;
        }
        Object items = map.get("items");
        if (items instanceof List<?> list) {
            return !list.isEmpty();
        }
        Object count = map.get("count");
        if (count instanceof Number number) {
            return number.doubleValue() > 0D;
        }
        for (Object value : map.values()) {
            if (value instanceof String text && !text.isBlank()) {
                return true;
            }
            if (value instanceof Map<?, ?> nested && !nested.isEmpty()) {
                return true;
            }
            if (value instanceof Iterable<?> iterable && iterable.iterator().hasNext()) {
                return true;
            }
            if (value instanceof Boolean bool && bool) {
                return true;
            }
            if (value instanceof Number number && number.doubleValue() > 0D) {
                return true;
            }
        }
        return false;
    }

    public List<Map<String, Object>> providerStatuses() {
        return EXPECTED_PROVIDERS.stream()
                .map(signalType -> {
                    List<OpsEvidenceProvider> matched = providers.stream()
                            .filter(provider -> signalType.equals(provider.signalType()))
                            .toList();
                    if (matched.isEmpty()) {
                        return Map.<String, Object>of(
                                "signalType", signalType,
                                "status", "MISSING",
                                "provider", "",
                                "healthy", false);
                    }
                    OpsEvidenceProvider provider = matched.get(0);
                    boolean available = provider.available();
                    return Map.<String, Object>of(
                            "signalType", signalType,
                            "status", available ? "ACTIVE" : "UNAVAILABLE",
                            "provider", provider.name(),
                            "healthy", available);
                })
                .toList();
    }
}
