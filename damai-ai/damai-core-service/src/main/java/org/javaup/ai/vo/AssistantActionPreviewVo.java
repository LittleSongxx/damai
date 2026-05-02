package org.javaup.ai.vo;

import lombok.Data;

@Data
public class AssistantActionPreviewVo {

    private Boolean actionRequired;

    private String actionId;

    private String actionType;

    private String previewSummary;
}
