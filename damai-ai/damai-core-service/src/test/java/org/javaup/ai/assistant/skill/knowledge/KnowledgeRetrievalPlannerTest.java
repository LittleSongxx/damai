package org.javaup.ai.assistant.skill.knowledge;

import org.javaup.ai.service.AdvancedQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class KnowledgeRetrievalPlannerTest {

    @Mock
    private AdvancedQueryService advancedQueryService;

    private KnowledgeRetrievalPlanner planner;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        planner = new KnowledgeRetrievalPlanner(advancedQueryService);
    }

    @Test
    void shouldUseLightweightPlanForSimpleQuery() {
        KnowledgeRetrievalPlan plan = planner.plan("退票");

        assertEquals(KnowledgeRetrievalPlan.Complexity.SIMPLE, plan.complexity());
        assertEquals(4, plan.topK());
        assertFalse(plan.enableRerank());
        assertEquals(List.of("退票"), plan.subQuestions());
    }

    @Test
    void shouldUseTopKTenAndRerankForMediumQuery() {
        KnowledgeRetrievalPlan plan = planner.plan("如何申请退票？");

        assertEquals(KnowledgeRetrievalPlan.Complexity.MEDIUM, plan.complexity());
        assertEquals(10, plan.topK());
        assertTrue(plan.enableRerank());
        assertEquals(List.of("如何申请退票？"), plan.subQuestions());
    }

    @Test
    void shouldKeepTopKTenForComplexQueryWithSubQuestions() {
        when(advancedQueryService.decomposeSubQuestions("退票和改签规则分别是什么"))
                .thenReturn(List.of("退票规则是什么", "改签规则是什么"));

        KnowledgeRetrievalPlan plan = planner.plan("退票和改签规则分别是什么");

        assertEquals(KnowledgeRetrievalPlan.Complexity.COMPLEX, plan.complexity());
        assertEquals(10, plan.topK());
        assertTrue(plan.enableRerank());
        assertEquals(List.of("退票规则是什么", "改签规则是什么"), plan.subQuestions());
    }
}
