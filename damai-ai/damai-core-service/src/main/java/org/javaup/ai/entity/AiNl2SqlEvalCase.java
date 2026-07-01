package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.javaup.ai.entity.base.BaseTableData;

@Data
@TableName("d_ai_nl2sql_eval_case")
public class AiNl2SqlEvalCase extends BaseTableData {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String caseId;
    private String question;
    private String expectedSql;
    private String expectedResultJson;
    private String expectedTableNames;
    private String category;
    private String difficulty;
}
