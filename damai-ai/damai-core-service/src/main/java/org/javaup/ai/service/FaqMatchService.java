package org.javaup.ai.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.entity.FaqEntry;
import org.javaup.ai.mapper.FaqEntryMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
public class FaqMatchService {

    private final FaqEntryMapper faqEntryMapper;

    public FaqMatchService(FaqEntryMapper faqEntryMapper) {
        this.faqEntryMapper = faqEntryMapper;
    }

    public FaqMatchResult match(String question) {
        if (!StringUtils.hasText(question)) {
            return null;
        }
        String trimmed = question.trim();
        FaqEntry entry = faqEntryMapper.selectOne(
                Wrappers.lambdaQuery(FaqEntry.class)
                        .eq(FaqEntry::getQuestion, trimmed)
                        .eq(FaqEntry::getStatus, 1)
                        .last("limit 1"));
        return entry != null ? new FaqMatchResult(entry.getFaqId(), entry.getQuestion()) : null;
    }

    public record FaqMatchResult(String faqId, String matchedQuestion) {}
}
