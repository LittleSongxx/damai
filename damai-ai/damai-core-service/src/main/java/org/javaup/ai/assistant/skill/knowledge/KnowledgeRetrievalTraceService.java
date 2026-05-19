package org.javaup.ai.assistant.skill.knowledge;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.context.AiRequestContext;
import org.javaup.ai.context.AiRequestContextHolder;
import org.javaup.ai.entity.AiRetrievalTrace;
import org.javaup.ai.mapper.AiRetrievalTraceMapper;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class KnowledgeRetrievalTraceService {

    private final AiRetrievalTraceMapper retrievalTraceMapper;

    public AiRetrievalTrace saveStageTrace(String traceType,
                                           String stepKey,
                                           String parentTraceId,
                                           String originalQuery,
                                           String rewrittenQuery,
                                           Object denseHits,
                                           Object sparseHits,
                                           Object fusedHits,
                                           Object finalHits,
                                           Map<String, Object> metadata) {
        AiRequestContext requestContext = AiRequestContextHolder.getOptional().orElse(null);
        AiRetrievalTrace trace = new AiRetrievalTrace();
        trace.setTraceId(nextId("retrieval"));
        trace.setRunId(requestContext == null ? null : requestContext.getRunId());
        trace.setChatId(requestContext == null ? null : requestContext.getConversationId());
        trace.setUserId(requestContext == null || requestContext.getUser() == null ? null : requestContext.getUser().getUserId());
        trace.setParentTraceId(parentTraceId);
        trace.setTraceType(traceType);
        trace.setStepKey(stepKey);
        trace.setOriginalQuery(originalQuery);
        trace.setRewrittenQuery(rewrittenQuery);
        trace.setDenseHitsJson(toJson(denseHits));
        trace.setSparseHitsJson(toJson(sparseHits));
        trace.setFusedHitsJson(toJson(fusedHits));
        trace.setFinalHitsJson(toJson(finalHits));
        trace.setMetadataJson(toJson(metadata));
        trace.setCreateTime(new Date());
        trace.setEditTime(new Date());
        trace.setStatus(1);
        retrievalTraceMapper.insert(trace);
        return trace;
    }

    public List<AiRetrievalTrace> listByRunId(String runId) {
        return retrievalTraceMapper.selectList(Wrappers.lambdaQuery(AiRetrievalTrace.class)
                .eq(AiRetrievalTrace::getRunId, runId)
                .eq(AiRetrievalTrace::getStatus, 1)
                .orderByAsc(AiRetrievalTrace::getCreateTime));
    }

    private String toJson(Object value) {
        return value == null ? null : JSON.toJSONString(value);
    }

    private String nextId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "");
    }
}
