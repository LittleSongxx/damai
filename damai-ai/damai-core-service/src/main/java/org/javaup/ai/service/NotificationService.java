package org.javaup.ai.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.entity.AiNotification;
import org.javaup.ai.entity.UserSubscription;
import org.javaup.ai.mapper.AiNotificationMapper;
import org.javaup.ai.mapper.UserSubscriptionMapper;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * 主动推送通知服务 - 参考美团/大麦的票务通知系统。
 * 支持开票提醒、订单状态变更、退款进度、演出临近提醒等。
 */
@Slf4j
@Service
public class NotificationService {

    private final AiNotificationMapper notificationMapper;
    private final UserSubscriptionMapper subscriptionMapper;
    private final RabbitTemplate rabbitTemplate;

    public NotificationService(AiNotificationMapper notificationMapper,
                                UserSubscriptionMapper subscriptionMapper,
                                RabbitTemplate rabbitTemplate) {
        this.notificationMapper = notificationMapper;
        this.subscriptionMapper = subscriptionMapper;
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 创建通知 (参考大麦APP的消息中心设计)
     */
    @Transactional
    public AiNotification createNotification(Long userId, String notificationType,
                                              String title, String content,
                                              String actionUrl, String actionText,
                                              String sendChannel) {
        AiNotification notification = new AiNotification();
        notification.setNotificationId(UUID.randomUUID().toString().replace("-", ""));
        notification.setUserId(userId);
        notification.setNotificationType(notificationType);
        notification.setTitle(title);
        notification.setContent(content);
        notification.setActionUrl(actionUrl);
        notification.setActionText(actionText);
        notification.setReadStatus(0);
        notification.setSendChannel(sendChannel != null ? sendChannel : "in_app");
        notification.setSentAt(new Date());
        notification.setCreateTime(new Date());
        notification.setEditTime(new Date());
        notification.setStatus(1);
        notificationMapper.insert(notification);

        // 异步发送到消息队列用于推送渠道分发
        try {
            rabbitTemplate.convertAndSend("damai.ai.notification.topic",
                    "notification." + notificationType, notification.getNotificationId());
        } catch (Exception e) {
            log.warn("Failed to publish notification to MQ: {}", e.getMessage());
        }

        return notification;
    }

    /**
     * 开票提醒 — 票务系统核心主动推送
     */
    @Transactional
    public void sendTicketOnSaleReminder(Long userId, String programName,
                                          String city, String onSaleTime, String programId) {
        String title = "你关注的演出即将开票";
        String content = String.format("《%s》（%s）将于 %s 开始售票，记得准时来抢票！", programName, city, onSaleTime);
        String actionUrl = "/program/" + programId;

        createNotification(userId, "TICKET_ON_SALE", title, content, actionUrl, "查看详情", "in_app");
    }

    /**
     * 订单状态通知
     */
    @Transactional
    public void sendOrderStatusNotification(Long userId, String orderNumber,
                                             String status, String programName) {
        String title = switch (status) {
            case "PAID" -> "订单支付成功";
            case "CANCELLED" -> "订单已取消";
            case "REFUNDING" -> "退款处理中";
            case "REFUNDED" -> "退款已到账";
            case "SHIPPED" -> "你的票品已发货";
            default -> "订单状态更新";
        };
        String content = String.format("订单 %s《%s》：%s", orderNumber, programName,
                switch (status) {
                    case "PAID" -> "已支付成功，请在演出当天凭电子票入场。";
                    case "REFUNDING" -> "退款正在处理中，预计3-5个工作日到账。";
                    case "REFUNDED" -> "退款已退回原支付方式。";
                    case "SHIPPED" -> "实体票已寄出，请注意查收快递。";
                    default -> "状态已更新为: " + status;
                });

        createNotification(userId, "ORDER_STATUS", title, content,
                "/order/" + orderNumber, "查看订单", "in_app");
    }

    /**
     * 演出临近提醒
     */
    @Transactional
    public void sendShowReminder(Long userId, String programName, String showTime, String venue) {
        String title = "演出提醒";
        String content = String.format("你购买的《%s》将于 %s 在 %s 开演，请提前安排好时间。", programName, showTime, venue);

        createNotification(userId, "SHOW_REMINDER", title, content, null, null, "in_app");
    }

    /**
     * 获取用户未读通知
     */
    public List<AiNotification> getUnreadNotifications(Long userId) {
        return notificationMapper.selectList(
                Wrappers.lambdaQuery(AiNotification.class)
                        .eq(AiNotification::getUserId, userId)
                        .eq(AiNotification::getReadStatus, 0)
                        .orderByDesc(AiNotification::getCreateTime));
    }

    /**
     * 获取用户全部通知
     */
    public List<AiNotification> getUserNotifications(Long userId, int limit) {
        return notificationMapper.selectList(
                Wrappers.lambdaQuery(AiNotification.class)
                        .eq(AiNotification::getUserId, userId)
                        .orderByDesc(AiNotification::getCreateTime)
                        .last("limit " + limit));
    }

    /**
     * 标记已读
     */
    @Transactional
    public void markAsRead(String notificationId) {
        AiNotification notification = notificationMapper.selectOne(
                Wrappers.lambdaQuery(AiNotification.class)
                        .eq(AiNotification::getNotificationId, notificationId));
        if (notification != null) {
            notification.setReadStatus(1);
            notification.setReadAt(new Date());
            notification.setEditTime(new Date());
            notificationMapper.updateById(notification);
        }
    }

    /**
     * 批量标记已读
     */
    @Transactional
    public int markAllAsRead(Long userId) {
        List<AiNotification> unread = getUnreadNotifications(userId);
        for (AiNotification notification : unread) {
            notification.setReadStatus(1);
            notification.setReadAt(new Date());
            notification.setEditTime(new Date());
            notificationMapper.updateById(notification);
        }
        return unread.size();
    }

    // ==================== 用户订阅管理 ====================

    /**
     * 添加订阅
     */
    @Transactional
    public UserSubscription addSubscription(Long userId, String subscriptionType,
                                             String subscriptionKey, String subscriptionValue) {
        // 去重
        UserSubscription existing = subscriptionMapper.selectOne(
                Wrappers.lambdaQuery(UserSubscription.class)
                        .eq(UserSubscription::getUserId, userId)
                        .eq(UserSubscription::getSubscriptionType, subscriptionType)
                        .eq(UserSubscription::getSubscriptionKey, subscriptionKey));
        if (existing != null) {
            existing.setEnabled(1);
            existing.setSubscriptionValue(subscriptionValue);
            existing.setEditTime(new Date());
            subscriptionMapper.updateById(existing);
            return existing;
        }

        UserSubscription sub = new UserSubscription();
        sub.setSubscriptionId(UUID.randomUUID().toString().replace("-", ""));
        sub.setUserId(userId);
        sub.setSubscriptionType(subscriptionType);
        sub.setSubscriptionKey(subscriptionKey);
        sub.setSubscriptionValue(subscriptionValue);
        sub.setEnabled(1);
        sub.setCreateTime(new Date());
        sub.setEditTime(new Date());
        sub.setStatus(1);
        subscriptionMapper.insert(sub);
        return sub;
    }

    /**
     * 取消订阅
     */
    @Transactional
    public void removeSubscription(Long userId, String subscriptionType, String subscriptionKey) {
        UserSubscription sub = subscriptionMapper.selectOne(
                Wrappers.lambdaQuery(UserSubscription.class)
                        .eq(UserSubscription::getUserId, userId)
                        .eq(UserSubscription::getSubscriptionType, subscriptionType)
                        .eq(UserSubscription::getSubscriptionKey, subscriptionKey));
        if (sub != null) {
            sub.setEnabled(0);
            sub.setEditTime(new Date());
            subscriptionMapper.updateById(sub);
        }
    }

    /**
     * 获取用户订阅列表
     */
    public List<UserSubscription> getUserSubscriptions(Long userId) {
        return subscriptionMapper.selectList(
                Wrappers.lambdaQuery(UserSubscription.class)
                        .eq(UserSubscription::getUserId, userId)
                        .eq(UserSubscription::getEnabled, 1));
    }

    /**
     * 根据订阅类型匹配用户 (用于批量推送)
     */
    public List<UserSubscription> findSubscriptionsByType(String subscriptionType) {
        return subscriptionMapper.selectList(
                Wrappers.lambdaQuery(UserSubscription.class)
                        .eq(UserSubscription::getSubscriptionType, subscriptionType)
                        .eq(UserSubscription::getEnabled, 1));
    }
}
