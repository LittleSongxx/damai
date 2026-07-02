package org.javaup.ai.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.context.AiRequestContextHolder;
import org.javaup.ai.entity.AiFeedback;
import org.javaup.ai.service.FeedbackAnalysisService;
import org.javaup.ai.service.FeedbackService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/assistant")
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackService feedbackService;
    private final FeedbackAnalysisService feedbackAnalysisService;

    @PostMapping("/customer-service/feedback")
    public ResponseEntity<Map<String, Object>> submit(@RequestBody Map<String, Object> body) {
        String runId = (String) body.get("runId");
        String conversationId = (String) body.get("conversationId");
        Long userId = AiRequestContextHolder.getRequiredUser().getUserId();
        String rating = (String) body.get("rating");
        String comment = (String) body.get("comment");

        AiFeedback feedback = feedbackService.submit(runId, conversationId, userId, rating, comment);

        // Feedback闭环: 异步分析负面反馈
        if ("down".equals(rating)) {
            try {
                feedbackAnalysisService.analyzeFeedback(feedback);
            } catch (Exception e) {
                log.warn("Feedback analysis failed (non-blocking): {}", e.getMessage());
            }
        }

        return ResponseEntity.ok(Map.of(
                "code", 0,
                "feedbackId", feedback.getFeedbackId(),
                "message", "success"
        ));
    }

    @GetMapping("/admin/customer-service/feedback/knowledge-gaps")
    public ResponseEntity<Map<String, Object>> getKnowledgeGaps(@RequestParam(defaultValue = "10") int limit) {
        return ResponseEntity.ok(Map.of(
                "code", 0,
                "gaps", feedbackAnalysisService.getTopKnowledgeGaps(limit)
        ));
    }
}
