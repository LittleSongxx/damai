package org.javaup.ai.assistant.skill.ops;

import lombok.Data;

@Data
public class OpsRcaRequest {

    private String query;

    private String serviceName;

    private String traceId;

    private String spanId;

    private String orderNumber;

    private String reservationId;

    private Long programId;

    private Integer windowMinutes;

    private String releaseVersion;

    private String configKey;

    private Integer changeWindowMinutes;
}
