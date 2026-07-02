package org.javaup.ai.controller;

import lombok.RequiredArgsConstructor;
import org.javaup.ai.common.ApiResponse;
import org.javaup.ai.entity.FaqEntry;
import org.javaup.ai.mapper.FaqEntryMapper;
import org.javaup.ai.service.FaqMatchService;
import org.springframework.web.bind.annotation.*;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/assistant/admin/faq")
@RequiredArgsConstructor
public class FaqController {

    private final FaqEntryMapper faqEntryMapper;
    private final FaqMatchService faqMatchService;

    @GetMapping
    public ApiResponse<List<FaqEntry>> list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Integer enabled) {
        var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FaqEntry>()
                .orderByDesc(FaqEntry::getPriority);
        if (category != null && !category.isBlank()) {
            wrapper.eq(FaqEntry::getCategory, category);
        }
        if (enabled != null) {
            wrapper.eq(FaqEntry::getEnabled, enabled);
        }
        return ApiResponse.ok(faqEntryMapper.selectList(wrapper));
    }

    @GetMapping("/{faqId}")
    public ApiResponse<FaqEntry> get(@PathVariable String faqId) {
        FaqEntry entry = faqEntryMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FaqEntry>()
                        .eq(FaqEntry::getFaqId, faqId));
        return entry != null ? ApiResponse.ok(entry) : ApiResponse.error("FAQ not found");
    }

    @PostMapping
    public ApiResponse<FaqEntry> create(@RequestBody Map<String, Object> body) {
        FaqEntry entry = new FaqEntry();
        entry.setFaqId(UUID.randomUUID().toString().replace("-", ""));
        entry.setQuestion((String) body.get("question"));
        entry.setSimilarQuestionsJson((String) body.get("similarQuestionsJson"));
        entry.setAnswer((String) body.get("answer"));
        entry.setCategory((String) body.get("category"));
        entry.setTags((String) body.get("tags"));
        entry.setKeywords((String) body.get("keywords"));
        entry.setPriority(body.get("priority") != null ? ((Number) body.get("priority")).intValue() : 0);
        entry.setEnabled(body.get("enabled") != null ? ((Number) body.get("enabled")).intValue() : 1);
        entry.setCreatedBy(body.get("createdBy") != null ? ((Number) body.get("createdBy")).longValue() : null);
        entry.setCreateTime(new Date());
        entry.setEditTime(new Date());
        entry.setStatus(1);
        faqEntryMapper.insert(entry);
        faqMatchService.indexFaq(entry);
        return ApiResponse.ok(entry);
    }

    @PutMapping("/{faqId}")
    public ApiResponse<FaqEntry> update(@PathVariable String faqId, @RequestBody Map<String, Object> body) {
        FaqEntry entry = faqEntryMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FaqEntry>()
                        .eq(FaqEntry::getFaqId, faqId));
        if (entry == null) return ApiResponse.error("FAQ not found");

        if (body.containsKey("question")) entry.setQuestion((String) body.get("question"));
        if (body.containsKey("similarQuestionsJson")) entry.setSimilarQuestionsJson((String) body.get("similarQuestionsJson"));
        if (body.containsKey("answer")) entry.setAnswer((String) body.get("answer"));
        if (body.containsKey("category")) entry.setCategory((String) body.get("category"));
        if (body.containsKey("tags")) entry.setTags((String) body.get("tags"));
        if (body.containsKey("keywords")) entry.setKeywords((String) body.get("keywords"));
        if (body.containsKey("priority")) entry.setPriority(((Number) body.get("priority")).intValue());
        if (body.containsKey("enabled")) entry.setEnabled(((Number) body.get("enabled")).intValue());
        entry.setUpdatedBy(body.get("updatedBy") != null ? ((Number) body.get("updatedBy")).longValue() : null);
        entry.setEditTime(new Date());
        faqEntryMapper.updateById(entry);
        faqMatchService.indexFaq(entry);
        return ApiResponse.ok(entry);
    }

    @DeleteMapping("/{faqId}")
    public ApiResponse<Void> delete(@PathVariable String faqId) {
        FaqEntry entry = faqEntryMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FaqEntry>()
                        .eq(FaqEntry::getFaqId, faqId));
        if (entry != null) {
            entry.setStatus(0);
            entry.setEditTime(new Date());
            faqEntryMapper.updateById(entry);
            faqMatchService.deleteFaqIndex(entry.getFaqId());
        }
        return ApiResponse.ok(null);
    }

    @PostMapping("/{faqId}/test")
    public ApiResponse<Map<String, Object>> testMatch(@PathVariable String faqId, @RequestBody Map<String, Object> body) {
        String query = (String) body.get("query");
        FaqMatchService.FaqMatchResult result = faqMatchService.match(query);
        if (result != null) {
            return ApiResponse.ok(Map.of("matched", true, "faqId", result.faqId(),
                    "matchMethod", result.matchMethod(), "matchScore", result.matchScore()));
        }
        return ApiResponse.ok(Map.of("matched", false));
    }
}
