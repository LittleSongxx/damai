package org.javaup.ai.assistant.skill.ops;

import java.time.Instant;
import java.util.Map;

public interface OpsEvidenceProvider {

    String name();

    String signalType();

    default boolean available() {
        return true;
    }

    Map<String, Object> collect(OpsRcaRequest request, Instant start, Instant end);
}
