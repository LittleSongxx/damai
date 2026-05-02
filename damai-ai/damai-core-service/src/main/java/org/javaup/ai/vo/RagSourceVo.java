package org.javaup.ai.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RagSourceVo {

    private String chunkId;

    private String title;

    private String source;

    private String section;

    private String snippet;

    private Double score;
}
