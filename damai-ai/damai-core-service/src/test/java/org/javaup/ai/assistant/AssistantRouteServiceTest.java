package org.javaup.ai.assistant;

import org.javaup.ai.structured.IntentRecognition;
import org.javaup.ai.structured.StructuredOutputService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AssistantRouteServiceTest {

    private StructuredOutputService structuredOutputService;
    private AssistantRouteService assistantRouteService;

    @BeforeEach
    void setUp() {
        structuredOutputService = mock(StructuredOutputService.class);
        assistantRouteService = new AssistantRouteService(structuredOutputService, mock(ChatClient.class));
    }

    @Test
    void shouldPreferBusinessWhenMessageContainsBusinessKeyword() {
        AssistantRouteDecision decision = assistantRouteService.route("帮我查下周上海演唱会票档");

        assertEquals(AssistantRouteType.BUSINESS, decision.getRouteType());
        assertFalse(decision.getFromFallback());
        assertFalse(decision.getClarificationRequired());
    }

    @Test
    void shouldPreferKnowledgeWhenRefundRuleAsked() {
        AssistantRouteDecision decision = assistantRouteService.route("开演前一天退票规则是什么");

        assertEquals(AssistantRouteType.KNOWLEDGE, decision.getRouteType());
        assertFalse(decision.getFromFallback());
        assertFalse(decision.getClarificationRequired());
    }

    @Test
    void shouldPreferBusinessOverOpsWhenMessageContainsMixedKeywords() {
        AssistantRouteDecision decision = assistantRouteService.route("帮我买票并看下 gateway cpu");

        assertEquals(AssistantRouteType.BUSINESS, decision.getRouteType());
        assertFalse(decision.getFromFallback());
    }

    @Test
    void shouldPreferGeneralWhenOpenDomainQuestionAsked() {
        AssistantRouteDecision decision = assistantRouteService.route("介绍一下这个歌手的代表作");

        assertEquals(AssistantRouteType.GENERAL, decision.getRouteType());
        assertFalse(decision.getFromFallback());
        assertFalse(decision.getClarificationRequired());
    }

    @Test
    void shouldUseStructuredFallbackWhenNoKeywordMatched() {
        IntentRecognition recognition = new IntentRecognition();
        recognition.setPrimaryIntent("CONSULT");
        when(structuredOutputService.recognizeIntent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("今天能做什么")))
                .thenReturn(recognition);

        AssistantRouteDecision decision = assistantRouteService.route("今天能做什么");

        assertEquals(AssistantRouteType.KNOWLEDGE, decision.getRouteType());
        assertTrue(decision.getFromFallback());
        assertFalse(decision.getClarificationRequired());
    }

    @Test
    void shouldAskClarificationWhenStructuredIntentIsUncertain() {
        IntentRecognition recognition = new IntentRecognition();
        recognition.setPrimaryIntent("OTHER");
        recognition.setConfidence(0.2D);
        recognition.setClarificationQuestion("你想查票还是查规则？");
        when(structuredOutputService.recognizeIntent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("看看这个")))
                .thenReturn(recognition);

        AssistantRouteDecision decision = assistantRouteService.route("看看这个");

        assertEquals(AssistantRouteType.BUSINESS, decision.getRouteType());
        assertTrue(decision.getClarificationRequired());
        assertEquals("你想查票还是查规则？", decision.getClarificationPrompt());
        assertNotNull(decision.getClarificationOptions());
    }

    @Test
    void shouldFallbackToGeneralWhenStructuredOutputFails() {
        when(structuredOutputService.recognizeIntent(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("随便聊聊")))
                .thenThrow(new IllegalStateException("boom"));

        AssistantRouteDecision decision = assistantRouteService.route("随便聊聊");

        assertEquals(AssistantRouteType.GENERAL, decision.getRouteType());
        assertTrue(decision.getFromFallback());
        assertFalse(decision.getClarificationRequired());
    }
}
