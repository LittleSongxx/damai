package org.javaup.ai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.entity.AiFeedback;
import org.javaup.ai.mapper.AiFeedbackMapper;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeedbackService {

    private final AiFeedbackMapper feedbackMapper;

    public AiFeedback submit(String runId, String conversationId, Long userId, String rating, String comment) {
        AiFeedback feedback = new AiFeedback();
        feedback.setFeedbackId(UUID.randomUUID().toString().replace("-", ""));
        feedback.setRunId(runId);
        feedback.setConversationId(conversationId);
        feedback.setUserId(userId);
        feedback.setRating(rating);
        feedback.setComment(comment);
        feedback.setCreateTime(new Date());
        feedback.setEditTime(new Date());
        feedback.setStatus(1);
        feedbackMapper.insert(feedback);
        log.info("Feedback submitted: runId={}, rating={}, feedbackId={}", runId, rating, feedback.getFeedbackId());
        return feedback;
    }
}
