package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.javaup.ai.entity.base.BaseTableData;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("d_ai_retrieval_trace")
public class AiRetrievalTrace extends BaseTableData {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String traceId;

    private String runId;

    private String chatId;

    private Long userId;

    private String parentTraceId;

    private String traceType;

    private String stepKey;

    private String originalQuery;

    private String rewrittenQuery;

    private String denseHitsJson;

    private String sparseHitsJson;

    private String fusedHitsJson;

    private String finalHitsJson;

    private String metadataJson;
}
