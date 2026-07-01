package org.javaup.ai.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.javaup.ai.entity.AiNl2SqlEvalCase;
import org.javaup.ai.entity.AiNl2SqlEvalResult;
import org.javaup.ai.entity.AiNl2SqlEvalRun;
import org.javaup.ai.mapper.AiNl2SqlEvalCaseMapper;
import org.javaup.ai.service.Nl2SqlEvalService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/nl2sql-eval")
@RequiredArgsConstructor
public class Nl2SqlEvalController {

    private final Nl2SqlEvalService evalService;
    private final AiNl2SqlEvalCaseMapper caseMapper;

    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> startEval(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String difficulty,
            @RequestBody(required = false) List<String> caseIds) {
        AiNl2SqlEvalRun run = evalService.startEvaluation(category, difficulty, caseIds);
        return ResponseEntity.ok(Map.of("code", 0, "evalRunId", run.getEvalRunId(),
                "totalCases", run.getTotalCases()));
    }

    @GetMapping("/status/{evalRunId}")
    public ResponseEntity<Map<String, Object>> getStatus(@PathVariable String evalRunId) {
        AiNl2SqlEvalRun run = evalService.getRunStatus(evalRunId);
        if (run == null) {
            return ResponseEntity.ok(Map.of("code", 1, "message", "not found"));
        }
        return ResponseEntity.ok(Map.of("code", 0, "data", run));
    }

    @GetMapping("/results/{evalRunId}")
    public ResponseEntity<Map<String, Object>> getResults(@PathVariable String evalRunId) {
        List<AiNl2SqlEvalResult> results = evalService.listRunResults(evalRunId);
        return ResponseEntity.ok(Map.of("code", 0, "data", results));
    }

    @GetMapping("/cases")
    public ResponseEntity<Map<String, Object>> listCases(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String difficulty) {
        LambdaQueryWrapper<AiNl2SqlEvalCase> wrapper = new LambdaQueryWrapper<AiNl2SqlEvalCase>()
                .eq(AiNl2SqlEvalCase::getStatus, 1);
        if (category != null && !category.isEmpty()) {
            wrapper.eq(AiNl2SqlEvalCase::getCategory, category);
        }
        if (difficulty != null && !difficulty.isEmpty()) {
            wrapper.eq(AiNl2SqlEvalCase::getDifficulty, difficulty);
        }
        wrapper.orderByAsc(AiNl2SqlEvalCase::getId);
        return ResponseEntity.ok(Map.of("code", 0, "data", caseMapper.selectList(wrapper)));
    }

    @PostMapping("/cases")
    public ResponseEntity<Map<String, Object>> createCase(@RequestBody AiNl2SqlEvalCase evalCase) {
        evalCase.setCaseId("nl2sql-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        evalCase.setCreateTime(new Date());
        evalCase.setEditTime(new Date());
        evalCase.setStatus(1);
        caseMapper.insert(evalCase);
        return ResponseEntity.ok(Map.of("code", 0, "caseId", evalCase.getCaseId()));
    }

    @PostMapping("/cases/batch")
    public ResponseEntity<Map<String, Object>> batchCreate(@RequestBody List<AiNl2SqlEvalCase> cases) {
        int count = 0;
        for (AiNl2SqlEvalCase c : cases) {
            c.setCaseId("nl2sql-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
            c.setCreateTime(new Date());
            c.setEditTime(new Date());
            c.setStatus(1);
            caseMapper.insert(c);
            count++;
        }
        return ResponseEntity.ok(Map.of("code", 0, "imported", count));
    }

    @PutMapping("/cases/{caseId}")
    public ResponseEntity<Map<String, Object>> updateCase(@PathVariable String caseId,
                                                           @RequestBody AiNl2SqlEvalCase updates) {
        AiNl2SqlEvalCase existing = caseMapper.selectOne(new LambdaQueryWrapper<AiNl2SqlEvalCase>()
                .eq(AiNl2SqlEvalCase::getCaseId, caseId));
        if (existing == null) {
            return ResponseEntity.ok(Map.of("code", 1, "message", "not found"));
        }
        if (updates.getQuestion() != null) existing.setQuestion(updates.getQuestion());
        if (updates.getExpectedSql() != null) existing.setExpectedSql(updates.getExpectedSql());
        if (updates.getExpectedResultJson() != null) existing.setExpectedResultJson(updates.getExpectedResultJson());
        if (updates.getExpectedTableNames() != null) existing.setExpectedTableNames(updates.getExpectedTableNames());
        if (updates.getCategory() != null) existing.setCategory(updates.getCategory());
        if (updates.getDifficulty() != null) existing.setDifficulty(updates.getDifficulty());
        existing.setEditTime(new Date());
        caseMapper.updateById(existing);
        return ResponseEntity.ok(Map.of("code", 0));
    }

    @DeleteMapping("/cases/{caseId}")
    public ResponseEntity<Map<String, Object>> deleteCase(@PathVariable String caseId) {
        AiNl2SqlEvalCase existing = caseMapper.selectOne(new LambdaQueryWrapper<AiNl2SqlEvalCase>()
                .eq(AiNl2SqlEvalCase::getCaseId, caseId));
        if (existing == null) {
            return ResponseEntity.ok(Map.of("code", 1, "message", "not found"));
        }
        existing.setStatus(0);
        existing.setEditTime(new Date());
        caseMapper.updateById(existing);
        return ResponseEntity.ok(Map.of("code", 0));
    }
}
