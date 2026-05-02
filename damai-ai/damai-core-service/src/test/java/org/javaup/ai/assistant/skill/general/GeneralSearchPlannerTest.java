package org.javaup.ai.assistant.skill.general;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneralSearchPlannerTest {

    private final GeneralSearchPlanner planner = new GeneralSearchPlanner();

    @Test
    void shouldRequireSearchForArtistBackgroundQuestion() {
        GeneralSearchPlan plan = planner.plan("介绍一下这个歌手的代表作和近期动态");

        assertTrue(plan.searchRequired());
    }

    @Test
    void shouldSkipSearchForCasualConversation() {
        GeneralSearchPlan plan = planner.plan("你好，今天聊点轻松的");

        assertFalse(plan.searchRequired());
    }
}
