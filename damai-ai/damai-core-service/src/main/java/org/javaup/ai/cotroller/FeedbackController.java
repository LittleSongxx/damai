package org.javaup.ai.cotroller;

import lombok.RequiredArgsConstructor;
import org.javaup.ai.entity.AiFeedback;
import org.javaup.ai.service.FeedbackService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/feedback")
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackService feedbackService;

    @PostMapping
    public ResponseEntity<Map<String, Object>> submit(@RequestBody Map<String, Object> body) {
        String runId = (String) body.get("runId");
        String conversationId = (String) body.get("conversationId");
        Long userId = body.get("userId") != null ? Long.valueOf(body.get("userId").toString()) : 0L;
        String rating = (String) body.get("rating");
        String comment = (String) body.get("comment");

        AiFeedback feedback = feedbackService.submit(runId, conversationId, userId, rating, comment);
        return ResponseEntity.ok(Map.of(
                "code", 0,
                "feedbackId", feedback.getFeedbackId(),
                "message", "success"
        ));
    }
}
