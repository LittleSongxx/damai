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
@TableName("d_ai_faq_entry")
public class FaqEntry extends BaseTableData {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String faqId;

    private String question;

    private String similarQuestionsJson;

    private String answer;

    private String category;

    private String tags;

    private String keywords;

    private Integer embeddingCached;

    private Integer priority;

    private Integer hitCount;

    private Integer enabled;

    private Date validFrom;

    private Date validUntil;

    private String region;

    private String audience;

    private Long createdBy;

    private Long updatedBy;
}
