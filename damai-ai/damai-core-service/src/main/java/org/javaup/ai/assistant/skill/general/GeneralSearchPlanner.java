package org.javaup.ai.assistant.skill.general;

import org.springframework.stereotype.Component;

@Component
public class GeneralSearchPlanner {

    public GeneralSearchPlan plan(String message) {
        String query = message == null ? "" : message.trim();
        String normalized = query.toLowerCase();
        boolean searchRequired = containsAny(normalized, "谁是", "是谁", "介绍", "代表作", "百科", "新闻", "资料", "最近", "歌手", "艺人", "专辑", "巡演", "新歌", "乐队", "动态", "背景");
        return new GeneralSearchPlan(query, searchRequired, searchRequired ? "keyword:search" : "keyword:chat");
    }

    private boolean containsAny(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
