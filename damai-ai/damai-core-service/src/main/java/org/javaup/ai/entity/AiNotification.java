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
@TableName("d_ai_notification")
public class AiNotification extends BaseTableData {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String notificationId;

    private Long userId;

    private String notificationType;

    private String title;

    private String content;

    private String actionUrl;

    private String actionText;

    private Integer readStatus;

    private Date readAt;

    private String sendChannel;

    private Date scheduledFor;

    private Date sentAt;
}
