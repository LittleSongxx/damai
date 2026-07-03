package org.javaup.ai.assistant.skill.ops;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class OpsRcaEvidenceService {

    private static final Pattern SERVICE_PATTERN = Pattern.compile("([a-z][a-z0-9-]+-service)");
    private static final Pattern TRACE_PATTERN = Pattern.compile("trace(?:id)?[=: ]+([A-Za-z0-9\\-_]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SPAN_PATTERN = Pattern.compile("span(?:id)?[=: ]+([A-Za-z0-9\\-_]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ORDER_PATTERN = Pattern.compile("order(?:number|No)?[=: ]+([A-Za-z0-9\\-_]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern RESERVATION_PATTERN = Pattern.compile("reservation(?:id)?[=: ]+([A-Za-z0-9\\-_]+)", Pattern.CASE_INSENSITIVE);

    private final OpsProviderRegistry providerRegistry;

    public Map<String, Object> buildEvidenceBundle(OpsRcaRequest request) {
        OpsRcaRequest normalized = normalize(request);
        Instant end = Instant.now();
        Instant start = end.minus(Duration.ofMinutes(normalized.getWindowMinutes()));
        Map<String, Object> providerEvidence = providerRegistry.collect(normalized, start, end);
        Map<String, Object> coverage = providerRegistry.coverage(providerEvidence);
        List<String> linkedSignals = linkedSignals(providerEvidence);
        List<Map<String, Object>> recommendedRunbooks = recommendedRunbooks(providerEvidence);
        String confidence = confidence(coverage, linkedSignals, providerEvidence);

        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("serviceName", normalized.getServiceName());
        bundle.put("traceId", value(normalized.getTraceId()));
        bundle.put("spanId", value(normalized.getSpanId()));
        bundle.put("orderNumber", value(normalized.getOrderNumber()));
        bundle.put("reservationId", value(normalized.getReservationId()));
        bundle.put("programId", normalized.getProgramId());
        bundle.put("timeWindow", Map.of(
                "start", start.toString(),
                "end", end.toString(),
                "minutes", normalized.getWindowMinutes()));
        bundle.put("signals", providerEvidence);
        bundle.put("evidenceCoverage", coverage);
        bundle.put("missingProviders", coverage.get("missingProviders"));
        bundle.put("confidence", confidence);
        bundle.put("humanReviewRequired", !"HIGH".equals(confidence)
                || !((List<?>) coverage.getOrDefault("missingProviders", List.of())).isEmpty());
        bundle.put("linkedSignals", linkedSignals);
        bundle.put("recommendedRunbooks", recommendedRunbooks);
        bundle.put("suggestedActions", suggestedActions(coverage, recommendedRunbooks));
        bundle.put("rcaSummary", Map.of(
                "suspectService", normalized.getServiceName(),
                "evidenceCoverage", coverage.get("coverageRatio"),
                "missingProviders", coverage.get("missingProviders"),
                "linkedSignals", linkedSignals,
                "requiresHumanReview", bundle.get("humanReviewRequired")));
        return bundle;
    }

    private OpsRcaRequest normalize(OpsRcaRequest request) {
        OpsRcaRequest normalized = new OpsRcaRequest();
        String query = request == null || !StringUtils.hasText(request.getQuery()) ? "" : request.getQuery();
        normalized.setQuery(query);
        normalized.setServiceName(StringUtils.hasText(request != null ? request.getServiceName() : null)
                ? request.getServiceName()
                : firstMatch(query, SERVICE_PATTERN, "damai-ai"));
        normalized.setTraceId(StringUtils.hasText(request != null ? request.getTraceId() : null)
                ? request.getTraceId()
                : firstMatch(query, TRACE_PATTERN, ""));
        normalized.setSpanId(StringUtils.hasText(request != null ? request.getSpanId() : null)
                ? request.getSpanId()
                : firstMatch(query, SPAN_PATTERN, ""));
        normalized.setOrderNumber(StringUtils.hasText(request != null ? request.getOrderNumber() : null)
                ? request.getOrderNumber()
                : firstMatch(query, ORDER_PATTERN, ""));
        normalized.setReservationId(StringUtils.hasText(request != null ? request.getReservationId() : null)
                ? request.getReservationId()
                : firstMatch(query, RESERVATION_PATTERN, ""));
        normalized.setProgramId(request == null ? null : request.getProgramId());
        int minutes = request != null && request.getWindowMinutes() != null && request.getWindowMinutes() > 0
                ? request.getWindowMinutes()
                : 30;
        normalized.setWindowMinutes(Math.min(24 * 60, Math.max(5, minutes)));
        normalized.setReleaseVersion(StringUtils.hasText(request != null ? request.getReleaseVersion() : null)
                ? request.getReleaseVersion()
                : "");
        normalized.setConfigKey(StringUtils.hasText(request != null ? request.getConfigKey() : null)
                ? request.getConfigKey()
                : "");
        int changeMinutes = request != null && request.getChangeWindowMinutes() != null && request.getChangeWindowMinutes() > 0
                ? request.getChangeWindowMinutes()
                : Math.max(60, normalized.getWindowMinutes() * 2);
        normalized.setChangeWindowMinutes(Math.min(7 * 24 * 60, Math.max(15, changeMinutes)));
        return normalized;
    }

    private List<String> linkedSignals(Map<String, Object> providerEvidence) {
        List<String> signals = new ArrayList<>();
        providerEvidence.forEach((signalType, raw) -> {
            if (!(raw instanceof Map<?, ?> wrapper) || wrapper.containsKey("error")
                    || !Boolean.TRUE.equals(wrapper.get("available"))) {
                return;
            }
            Object payload = wrapper.get("payload");
            if (hasEvidence(payload)) {
                signals.add(signalType);
            }
        });
        return signals;
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
            if (value instanceof String text && StringUtils.hasText(text)) {
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

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> recommendedRunbooks(Map<String, Object> providerEvidence) {
        Object runbookWrapper = providerEvidence.get("runbooks");
        if (!(runbookWrapper instanceof Map<?, ?> wrapper)) {
            return List.of();
        }
        Object payload = wrapper.get("payload");
        if (!(payload instanceof Map<?, ?> payloadMap)) {
            return List.of();
        }
        Object items = payloadMap.get("items");
        if (!(items instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .limit(5)
                .toList();
    }

    private String confidence(Map<String, Object> coverage,
                              List<String> linkedSignals,
                              Map<String, Object> providerEvidence) {
        double ratio = number(coverage.get("relevanceRatio"));
        boolean hasAlerts = providerEvidence.containsKey("alerts") && linkedSignals.contains("alerts");
        boolean hasBusiness = providerEvidence.containsKey("businessEvents") && linkedSignals.contains("businessEvents");
        if (ratio >= 0.75D && linkedSignals.size() >= 4 && (hasAlerts || hasBusiness)) {
            return "HIGH";
        }
        if (ratio >= 0.45D && linkedSignals.size() >= 2) {
            return "MEDIUM";
        }
        return "LOW";
    }

    private List<String> suggestedActions(Map<String, Object> coverage,
                                          List<Map<String, Object>> recommendedRunbooks) {
        List<String> actions = new ArrayList<>();
        if (!recommendedRunbooks.isEmpty()) {
            actions.add("review recommended runbooks; execution remains manual and confirmation-gated");
        }
        Object missing = coverage.get("missingProviders");
        if (missing instanceof List<?> list && !list.isEmpty()) {
            actions.add("connect missing evidence providers before making irreversible remediation decisions: " + list);
        }
        actions.add("correlate alert time, trace path, business event status, and recent change window with the on-call owner");
        return actions;
    }

    private String firstMatch(String text, Pattern pattern, String fallback) {
        if (!StringUtils.hasText(text)) {
            return fallback;
        }
        java.util.regex.Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : fallback;
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return 0D;
        }
    }
}
