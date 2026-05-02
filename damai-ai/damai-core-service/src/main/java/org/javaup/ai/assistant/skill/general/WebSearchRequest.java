package org.javaup.ai.assistant.skill.general;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebSearchRequest {

    private String query;

    private Integer maxResults;
}
