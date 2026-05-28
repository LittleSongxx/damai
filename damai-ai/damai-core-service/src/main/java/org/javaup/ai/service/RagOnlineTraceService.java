package org.javaup.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.entity.AiRagOnlineTrace;
import org.javaup.ai.mapper.AiRagOnlineTraceMapper;
import org.javaup.ai.vo.RagOnlineTraceRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RagOnlineTraceService {

    private final AiRagOnlineTraceMapper traceMapper;

    public AiRagOnlineTrace createTrace(RagOnlineTraceRequest request) {
        AiRagOnlineTrace trace = new AiRagOnlineTrace();
        trace.setTraceId(StringUtils.hasText(request.getTraceId()) ? request.getTraceId() : UUID.randomUUID().toString().replace("-", ""));
        trace.setConversationId(request.getConversationId());
        trace.setRunId(request.getRunId());
        trace.setUserId(request.getUserId());
        trace.setQuestion(request.getQuestion());
        trace.setRewrittenQuery(request.getRewrittenQuery());
        trace.setSubQuestionsJson(request.getSubQuestionsJson());
        trace.setRetrievedChunksJson(request.getRetrievedChunksJson());
        trace.setFinalChunksJson(request.getFinalChunksJson());
        trace.setGeneratedAnswer(request.getGeneratedAnswer());
        trace.setCitationsJson(request.getCitationsJson());
        trace.setRetrievalConfigId(request.getRetrievalConfigId());
        trace.setModelName(request.getModelName());
        trace.setPromptVersion(request.getPromptVersion());
        trace.setRewriteLatencyMs(request.getRewriteLatencyMs());
        trace.setRetrievalLatencyMs(request.getRetrievalLatencyMs());
        trace.setRerankLatencyMs(request.getRerankLatencyMs());
        trace.setGenerationLatencyMs(request.getGenerationLatencyMs());
        trace.setTotalLatencyMs(request.getTotalLatencyMs());
        trace.setInputTokens(request.getInputTokens());
        trace.setOutputTokens(request.getOutputTokens());
        trace.setTotalTokens(request.getTotalTokens());
        trace.setEstimatedCost(request.getEstimatedCost());
        trace.setCacheHit(request.getCacheHit());
        trace.setConfidenceLevel(request.getConfidenceLevel());
        trace.setConfidenceScore(request.getConfidenceScore());
        trace.setErrorCode(request.getErrorCode());
        trace.setErrorMessage(request.getErrorMessage());
        trace.setFeedbackType(request.getFeedbackType());
        trace.setMetadataJson(request.getMetadataJson());
        trace.setCreateTime(new Date());
        trace.setEditTime(new Date());
        trace.setStatus(1);
        traceMapper.insert(trace);
        return trace;
    }

    public List<AiRagOnlineTrace> listTraces(String conversationId, String feedbackType) {
        LambdaQueryWrapper<AiRagOnlineTrace> wrapper = new LambdaQueryWrapper<AiRagOnlineTrace>()
                .eq(AiRagOnlineTrace::getStatus, 1);
        if (StringUtils.hasText(conversationId)) {
            wrapper.eq(AiRagOnlineTrace::getConversationId, conversationId);
        }
        if (StringUtils.hasText(feedbackType)) {
            wrapper.eq(AiRagOnlineTrace::getFeedbackType, feedbackType);
        }
        wrapper.orderByDesc(AiRagOnlineTrace::getCreateTime);
        return traceMapper.selectList(wrapper);
    }

    public AiRagOnlineTrace getTrace(String traceId) {
        if (!StringUtils.hasText(traceId)) {
            return null;
        }
        return traceMapper.selectOne(new LambdaQueryWrapper<AiRagOnlineTrace>()
                .eq(AiRagOnlineTrace::getTraceId, traceId)
                .eq(AiRagOnlineTrace::getStatus, 1)
                .last("limit 1"));
    }
}
