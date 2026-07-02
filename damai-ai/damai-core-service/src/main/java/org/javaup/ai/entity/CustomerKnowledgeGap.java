package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.javaup.ai.entity.base.BaseTableData;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("d_ai_customer_knowledge_gap")
public class CustomerKnowledgeGap extends BaseTableData {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String gapId;

    private String sourceType;

    private String sourceId;

    private String conversationId;

    private Long userId;

    private String intentCode;

    private String issueCategory;

    private String question;

    private String evidenceJson;

    private String gapStatus;

    private String draftId;

    private String evalCaseId;

    private String operatorId;
}
