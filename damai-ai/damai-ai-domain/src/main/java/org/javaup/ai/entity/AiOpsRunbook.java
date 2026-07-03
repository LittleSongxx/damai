package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import org.javaup.ai.entity.base.BaseTableData;

@Data
@TableName("d_ai_ops_runbook")
public class AiOpsRunbook extends BaseTableData {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String runbookId;
    private String serviceName;
    private String scenarioKey;
    private String title;
    private String recommendation;
    private String riskLevel;
    private Integer executable;
    private Integer reviewRequired;
    private String runbookStatus;
    private String operatorId;
    private String extJson;
}
