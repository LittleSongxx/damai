package org.javaup.ai.guardrails;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class ToxicityChecker {

    private static final Set<String> TOXIC_KEYWORDS = Set.of(
            "自杀", "杀人", "炸弹", "制毒", "恐怖袭击", "暴力",
            "种族歧视", "色情", "裸体", "赌博网站", "洗钱"
    );

    public boolean isToxic(String text) {
        if (text == null || text.isEmpty()) return false;
        String lower = text.toLowerCase();
        return TOXIC_KEYWORDS.stream().anyMatch(lower::contains);
    }

    public List<String> detectToxicTerms(String text) {
        if (text == null || text.isEmpty()) return List.of();
        String lower = text.toLowerCase();
        return TOXIC_KEYWORDS.stream()
                .filter(lower::contains)
                .map(k -> "TOXICITY:" + k)
                .toList();
    }
}
