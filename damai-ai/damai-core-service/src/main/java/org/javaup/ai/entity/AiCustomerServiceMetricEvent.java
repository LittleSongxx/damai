package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.javaup.ai.entity.base.BaseTableData;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("d_ai_customer_service_metric_event")
public class AiCustomerServiceMetricEvent extends BaseTableData {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String metricId;

    private String runId;

    private String conversationId;

    private Long userId;

    private String metricType;

    private Double metricValue;

    private Long latencyMs;

    private String dimensionsJson;
}
