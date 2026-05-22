package org.javaup.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.UUID;

@Slf4j
@Service
public class NotificationService {

    public void createNotification(Long userId, String type, String title,
                                   String content, String link, String actionLabel,
                                   String channel) {
        log.info("Notification created: userId={}, type={}, title={}, channel={}",
                userId, type, title, channel);
    }
}
