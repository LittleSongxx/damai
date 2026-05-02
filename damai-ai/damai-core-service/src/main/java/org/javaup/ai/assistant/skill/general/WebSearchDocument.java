package org.javaup.ai.assistant.skill.general;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebSearchDocument {

    private String title;

    private String url;

    private String snippet;

    private String source;

    private Double score;
}
