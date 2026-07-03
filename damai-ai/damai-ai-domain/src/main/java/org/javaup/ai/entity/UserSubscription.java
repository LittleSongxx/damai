package org.javaup.ai.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.javaup.ai.entity.base.BaseTableData;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("d_ai_user_subscription")
public class UserSubscription extends BaseTableData {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String subscriptionId;

    private Long userId;

    private String subscriptionType;

    private String subscriptionKey;

    private String subscriptionValue;

    private Integer enabled;
}
