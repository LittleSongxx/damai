package org.javaup.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.entity.AiRagBadCase;
import org.javaup.ai.entity.AiRagEvalCase;
import org.javaup.ai.entity.AiRagOnlineTrace;
import org.javaup.ai.mapper.AiRagBadCaseMapper;
import org.javaup.ai.mapper.AiRagEvalCaseMapper;
import org.javaup.ai.vo.RagBadCaseConvertRequest;
import org.javaup.ai.vo.RagBadCaseRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RagBadCaseService {

    private final AiRagBadCaseMapper badCaseMapper;
    private final AiRagEvalCaseMapper caseMapper;
    private final RagOnlineTraceService traceService;

    public AiRagBadCase createBadCase(RagBadCaseRequest request) {
        AiRagBadCase badCase = new AiRagBadCase();
        badCase.setBadCaseId(UUID.randomUUID().toString().replace("-", ""));
        badCase.setTraceId(request.getTraceId());
        badCase.setQuestion(request.getQuestion());
        badCase.setGeneratedAnswer(request.getGeneratedAnswer());
        badCase.setRetrievedChunksJson(request.getRetrievedChunksJson());
        badCase.setExpectedAnswer(request.getExpectedAnswer());
        badCase.setExpectedChunks(request.getExpectedChunks());
        badCase.setFeedbackType(request.getFeedbackType());
        badCase.setFailureType(request.getFailureType());
        badCase.setCategory(request.getCategory());
        badCase.setDifficulty(request.getDifficulty());
        badCase.setCaseType(request.getCaseType());
        badCase.setOperatorNote(request.getOperatorNote());
        badCase.setReviewStatus(StringUtils.hasText(request.getReviewStatus()) ? request.getReviewStatus() : "PENDING");
        badCase.setConvertedToEvalCase(0);
        badCase.setCreateTime(new Date());
        badCase.setEditTime(new Date());
        badCase.setStatus(1);
        badCaseMapper.insert(badCase);
        return badCase;
    }

    public AiRagBadCase createFromTrace(String traceId, String failureType, String operatorNote) {
        AiRagOnlineTrace trace = traceService.getTrace(traceId);
        if (trace == null) {
            return null;
        }
        RagBadCaseRequest request = new RagBadCaseRequest();
        request.setTraceId(trace.getTraceId());
        request.setQuestion(trace.getQuestion());
        request.setGeneratedAnswer(trace.getGeneratedAnswer());
        request.setRetrievedChunksJson(trace.getFinalChunksJson() != null ? trace.getFinalChunksJson() : trace.getRetrievedChunksJson());
        request.setFeedbackType(trace.getFeedbackType());
        request.setFailureType(failureType);
        request.setOperatorNote(operatorNote);
        request.setReviewStatus("PENDING");
        return createBadCase(request);
    }

    public List<AiRagBadCase> listBadCases(String reviewStatus, String failureType) {
        LambdaQueryWrapper<AiRagBadCase> wrapper = new LambdaQueryWrapper<AiRagBadCase>()
                .eq(AiRagBadCase::getStatus, 1);
        if (StringUtils.hasText(reviewStatus)) {
            wrapper.eq(AiRagBadCase::getReviewStatus, reviewStatus);
        }
        if (StringUtils.hasText(failureType)) {
            wrapper.eq(AiRagBadCase::getFailureType, failureType);
        }
        wrapper.orderByDesc(AiRagBadCase::getCreateTime);
        return badCaseMapper.selectList(wrapper);
    }

    public AiRagBadCase getBadCase(String badCaseId) {
        if (!StringUtils.hasText(badCaseId)) {
            return null;
        }
        return badCaseMapper.selectOne(new LambdaQueryWrapper<AiRagBadCase>()
                .eq(AiRagBadCase::getBadCaseId, badCaseId)
                .eq(AiRagBadCase::getStatus, 1)
                .last("limit 1"));
    }

    public AiRagEvalCase convertToEvalCase(String badCaseId, RagBadCaseConvertRequest request) {
        AiRagBadCase badCase = getBadCase(badCaseId);
        if (badCase == null) {
            return null;
        }
        AiRagEvalCase evalCase = new AiRagEvalCase();
        evalCase.setCaseId(UUID.randomUUID().toString().replace("-", ""));
        evalCase.setQuestion(badCase.getQuestion());
        evalCase.setExpectedAnswer(StringUtils.hasText(request.getExpectedAnswer()) ? request.getExpectedAnswer() : badCase.getExpectedAnswer());
        evalCase.setExpectedChunks(StringUtils.hasText(request.getExpectedChunks()) ? request.getExpectedChunks() : badCase.getExpectedChunks());
        evalCase.setCategory(StringUtils.hasText(request.getCategory()) ? request.getCategory() : badCase.getCategory());
        evalCase.setDifficulty(StringUtils.hasText(request.getDifficulty()) ? request.getDifficulty() : badCase.getDifficulty());
        evalCase.setCaseType(StringUtils.hasText(request.getCaseType()) ? request.getCaseType() : badCase.getCaseType());
        evalCase.setDatasetId(StringUtils.hasText(request.getDatasetId()) ? request.getDatasetId() : "default-golden");
        evalCase.setDatasetVersion(StringUtils.hasText(request.getDatasetVersion()) ? request.getDatasetVersion() : "v1");
        evalCase.setReviewStatus("PENDING");
        evalCase.setStatus(1);
        evalCase.setCreateTime(new Date());
        evalCase.setEditTime(new Date());
        caseMapper.insert(evalCase);

        badCase.setConvertedToEvalCase(1);
        badCase.setConvertedCaseId(evalCase.getCaseId());
        badCase.setEditTime(new Date());
        badCaseMapper.updateById(badCase);
        return evalCase;
    }
}
