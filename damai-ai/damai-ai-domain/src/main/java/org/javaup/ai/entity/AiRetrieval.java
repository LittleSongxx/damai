package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.javaup.ai.entity.base.BaseTableData;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("d_ai_retrieval")
public class AiRetrieval extends BaseTableData {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String retrievalId;

    private String runId;

    private String conversationId;

    private Long userId;

    private String originalQuery;

    private String normalizedQuery;

    private String rewrittenQuery;

    private String denseHitsJson;

    private String sparseHitsJson;

    private String fusedHitsJson;

    private String finalHitsJson;

    private Double confidenceScore;

    private String confidenceLevel;

    private String correctiveAction;

    private String retrievalPlanJson;
}
