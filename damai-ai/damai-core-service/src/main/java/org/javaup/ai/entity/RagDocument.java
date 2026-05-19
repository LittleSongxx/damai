package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.javaup.ai.entity.base.BaseTableData;

import java.util.Date;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("d_ai_rag_document")
public class RagDocument extends BaseTableData {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String docUid;
    private String title;
    private String source;
    private String sourceFile;
    private String category;
    private String tags;
    private String docStatus;
    private String fileType;
    private String contentHash;
    private String metadataJson;
    private Integer chunkCount;
    private Integer version;
    private Date validFrom;
    private Date validUntil;
    private String region;
    private String audience;
    private Integer priority;
}
