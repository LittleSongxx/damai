package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.javaup.ai.entity.base.BaseTableData;

@Data
@TableName("d_ai_nl2sql_semantic_catalog")
public class AiNl2SqlSemanticCatalog extends BaseTableData {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String catalogId;
    private String datasourceKey;
    private String semanticType;
    private String semanticKey;
    private String displayName;
    private String datasetName;
    private String tableName;
    private String columnName;
    private String expressionSql;
    private String allowedView;
    private String sensitivityLevel;
    private String exampleSql;
    private Integer versionNo;
    private String catalogStatus;
    private String operatorId;
    private String extJson;
}
