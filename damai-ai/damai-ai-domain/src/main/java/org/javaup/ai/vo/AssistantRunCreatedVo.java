package org.javaup.ai.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AssistantRunCreatedVo {

    private String runId;

    private String chatId;

    private String status;

    private String eventStreamPath;
}
