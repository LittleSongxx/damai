package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.javaup.ai.entity.base.BaseTableData;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("d_ai_rag_chunk")
public class RagChunk extends BaseTableData {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String chunkUid;
    private Long docId;
    private Long parentChunkId;
    private String chunkType;
    private Integer chunkIndex;
    private Integer totalChunks;
    private String headingPath;
    private String question;
    private String text;
    private String contextText;
    private String contentHash;
    private String metadataJson;
    private Long qdrantPointId;
    private String esDocId;
    private Long prevChunkId;
    private Long nextChunkId;
    private Boolean embeddingCached;
    private String hypotheticalQuestionsJson;
    private String summaryText;
    private String entitiesJson;
}
