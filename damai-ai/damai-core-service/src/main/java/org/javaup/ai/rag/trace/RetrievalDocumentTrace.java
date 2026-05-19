package org.javaup.ai.rag.trace;

import lombok.Builder;
import lombok.Data;

/**
 * Per-document score tracking across each stage of the retrieval pipeline.
 * Records raw channel scores, RRF score, rerank score, gate status, and selection reason.
 *
 * Inspired by super-agent's RetrievalResultView + ChannelExecutionView.
 */
@Data
@Builder
public class RetrievalDocumentTrace {
    private String chunkId;
    private String title;
    private String source;

    // Channel-level scores (one per channel that returned this chunk)
    private Double denseRawScore;
    private Integer denseRawRank;
    private Double sparseRawScore;
    private Integer sparseRawRank;
    private Double hydeRawScore;
    private Integer hydeRawRank;

    // Post-fusion
    private Double rrfScore;
    private Integer rrfRank;

    // Post-rerank
    private Double rerankScore;
    private Integer rerankRank;

    // Gate status
    private boolean passedDenseGate;
    private boolean passedSparseGate;
    private boolean passedRerankGate;

    // Selection
    private boolean selectedInFinal;
    private String selectionReason;
    private String channelOrigin;
}
