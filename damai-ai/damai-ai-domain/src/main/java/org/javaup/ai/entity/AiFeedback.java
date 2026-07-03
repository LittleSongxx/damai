package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.javaup.ai.entity.base.BaseTableData;

@Data
@TableName("d_ai_feedback")
public class AiFeedback extends BaseTableData {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String feedbackId;
    private String runId;
    private String conversationId;
    private Long userId;
    private String rating;
    private String comment;
}
