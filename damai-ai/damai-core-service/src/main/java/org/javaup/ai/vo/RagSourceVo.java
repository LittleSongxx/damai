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

    /**
     * Optional parent block identifier for parent-child block elevation.
     * When set, child chunks can be elevated to their parent block with aggregated scoring.
     */
    private String parentBlockId;

    /**
     * Name of the retrieval channel that produced this result (dense/sparse/hyde).
     */
    private String channelName;

    /**
     * Document expiry timestamp (millis). Chunks from expired documents
     * are filtered out by TemporalValidityPostProcessor during retrieval.
     */
    private Long validUntil;

    /**
     * Document version number. Higher values indicate newer revisions.
     */
    private Integer version;
}
