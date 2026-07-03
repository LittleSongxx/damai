package org.javaup.ai.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CustomerHotQuestionVo {

    private String questionId;

    private String scene;

    private String displayText;

    private String queryText;

    private String intentCode;

    private String routeHint;

    private String answerMode;

    private Integer priority;

    private List<String> tags;
}
