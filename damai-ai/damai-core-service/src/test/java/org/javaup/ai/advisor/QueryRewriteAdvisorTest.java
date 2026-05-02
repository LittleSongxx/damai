package org.javaup.ai.advisor;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class QueryRewriteAdvisorTest {

    @Test
    void shouldRewritePromptAndPersistRewrittenQueryInContext() {
        QueryRewriteAdvisor advisor = QueryRewriteAdvisor.builder().build();
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt("这场演出支持退票吗"))
                .context(Map.of("chatId", "chat-1"))
                .build();

        ChatClientRequest rewritten = advisor.before(request, mock(AdvisorChain.class));

        assertNotSame(request, rewritten);
        assertTrue(rewritten.prompt().getUserMessage().getText().contains("退款"));
        assertEquals(rewritten.prompt().getUserMessage().getText(), rewritten.context().get("rewrittenQuery"));
        assertEquals("chat-1", rewritten.context().get("chatId"));
    }

    @Test
    void shouldKeepOriginalRequestWhenNoRewriteNeeded() {
        QueryRewriteAdvisor advisor = QueryRewriteAdvisor.builder().build();
        ChatClientRequest request = ChatClientRequest.builder()
                .prompt(new Prompt("今天有什么规则"))
                .context(Map.of())
                .build();

        ChatClientRequest result = advisor.before(request, mock(AdvisorChain.class));

        assertSame(request, result);
    }
}
