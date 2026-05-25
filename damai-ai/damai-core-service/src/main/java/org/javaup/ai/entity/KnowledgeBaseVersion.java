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
@TableName("d_ai_knowledge_base_version")
public class KnowledgeBaseVersion extends BaseTableData {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String versionId;

    private String docUid;

    private Integer versionNumber;

    private String content;

    private String changeSummary;

    private Long changedBy;

    private String publishStatus;

    private Long reviewedBy;

    private String reviewComment;

    private Date publishedAt;
}
