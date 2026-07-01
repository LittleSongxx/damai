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
@TableName("d_ai_customer_hot_question")
public class AiCustomerHotQuestion extends BaseTableData {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String questionId;

    private String scene;

    private String displayText;

    private String queryText;

    private String intentCode;

    private String routeHint;

    private String answerMode;

    private String cachedAnswerJson;

    private String sourceRefsJson;

    private String tagsJson;

    private Integer priority;

    private Integer enabled;

    private Integer cacheVersion;

    private Date expireAt;
}
