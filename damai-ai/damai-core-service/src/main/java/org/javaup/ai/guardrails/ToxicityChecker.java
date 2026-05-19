package org.javaup.ai.guardrails;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class ToxicityChecker {

    private static final Set<String> TOXIC_KEYWORDS = Set.of(
            // Violence / self-harm
            "自杀", "杀人", "炸弹", "自残", "跳楼", "割腕", "安眠药", "毒药", "氰化物",
            // Terrorism
            "恐怖袭击", "暴力",
            // Hate speech
            "种族歧视", "纳粹", "希特勒", "排外", "仇视",
            // Adult content / child safety
            "色情", "裸体", "裸照", "幼童", "未成年色情",
            // Drugs
            "制毒", "毒品", "冰毒", "海洛因", "摇头丸", "大麻", "可卡因", "鸦片", "吗啡",
            // Illegal gambling
            "赌博网站", "洗钱", "赌场", "六合彩", "时时彩", "赌球", "老虎机", "百家乐",
            // Fraud/scams
            "诈骗", "传销", "网络钓鱼", "冒充公检法", "假冒客服", "杀猪盘",
            // Harassment / stalking
            "人肉", "网暴", "恐吓", "威胁", "跟踪", "骚扰电话",
            // Weapons
            "枪支", "弹药", "爆炸物", "管制刀具", "雷管",
            // Chemical / bio
            "炭疽", "蓖麻毒素", "沙林"
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
