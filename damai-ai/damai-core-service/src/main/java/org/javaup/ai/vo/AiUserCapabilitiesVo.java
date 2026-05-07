package org.javaup.ai.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AiUserCapabilitiesVo {

    private Long userId;

    private Boolean admin;

    private List<String> allowedRoutes;

    private List<AssistantSkillVo> skills;
}
