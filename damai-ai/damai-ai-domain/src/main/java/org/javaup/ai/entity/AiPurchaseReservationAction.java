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
@TableName("d_ai_purchase_reservation_action")
public class AiPurchaseReservationAction extends BaseTableData {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String reservationId;

    private String actionId;

    private String runId;

    private Long userId;

    private Long programId;

    private Long ticketCategoryId;

    private Integer ticketCount;

    private String reservationStatus;

    private String idempotencyKey;

    private Date expiresAt;

    private Date releasedAt;

    private String releaseReason;

    private String orderNumber;

    private String failureMessage;

    private String sagaStatus;

    private String failureCategory;

    private String compensationStatus;

    private String confirmAttemptId;

    private Date lastCheckedAt;

    private Integer retryCount;

    private String requestHash;

    private String operatorId;

    private String extJson;
}
