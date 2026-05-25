package org.javaup.ai.controller;

import lombok.RequiredArgsConstructor;
import org.javaup.ai.common.ApiResponse;
import org.javaup.ai.context.AiRequestContextHolder;
import org.javaup.ai.entity.AiNotification;
import org.javaup.ai.entity.UserSubscription;
import org.javaup.ai.service.NotificationService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public ApiResponse<List<AiNotification>> list(@RequestParam(defaultValue = "50") int limit) {
        Long userId = AiRequestContextHolder.getRequiredUser().getUserId();
        return ApiResponse.ok(notificationService.getUserNotifications(userId, limit));
    }

    @GetMapping("/unread")
    public ApiResponse<List<AiNotification>> unread() {
        Long userId = AiRequestContextHolder.getRequiredUser().getUserId();
        return ApiResponse.ok(notificationService.getUnreadNotifications(userId));
    }

    @GetMapping("/unread/count")
    public ApiResponse<Map<String, Integer>> unreadCount() {
        Long userId = AiRequestContextHolder.getRequiredUser().getUserId();
        int count = notificationService.getUnreadNotifications(userId).size();
        return ApiResponse.ok(Map.of("count", count));
    }

    @PostMapping("/{notificationId}/read")
    public ApiResponse<Void> markRead(@PathVariable String notificationId) {
        notificationService.markAsRead(notificationId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/read-all")
    public ApiResponse<Map<String, Integer>> markAllRead() {
        Long userId = AiRequestContextHolder.getRequiredUser().getUserId();
        int count = notificationService.markAllAsRead(userId);
        return ApiResponse.ok(Map.of("markedCount", count));
    }

    // ==================== 订阅管理 ====================

    @GetMapping("/subscriptions")
    public ApiResponse<List<UserSubscription>> getSubscriptions() {
        Long userId = AiRequestContextHolder.getRequiredUser().getUserId();
        return ApiResponse.ok(notificationService.getUserSubscriptions(userId));
    }

    @PostMapping("/subscriptions")
    public ApiResponse<UserSubscription> addSubscription(@RequestBody Map<String, String> body) {
        Long userId = AiRequestContextHolder.getRequiredUser().getUserId();
        UserSubscription sub = notificationService.addSubscription(
                userId,
                body.get("subscriptionType"),
                body.get("subscriptionKey"),
                body.get("subscriptionValue"));
        return ApiResponse.ok(sub);
    }

    @DeleteMapping("/subscriptions")
    public ApiResponse<Void> removeSubscription(@RequestParam String subscriptionType, @RequestParam String subscriptionKey) {
        Long userId = AiRequestContextHolder.getRequiredUser().getUserId();
        notificationService.removeSubscription(userId, subscriptionType, subscriptionKey);
        return ApiResponse.ok(null);
    }
}
