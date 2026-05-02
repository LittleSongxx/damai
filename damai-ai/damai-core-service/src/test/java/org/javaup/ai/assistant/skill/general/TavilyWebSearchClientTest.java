package org.javaup.ai.assistant.skill.general;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.javaup.ai.config.WebSearchProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.status;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TavilyWebSearchClientTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @Test
    void shouldFallbackToNextApiKeyWhenPreviousKeyFails() {
        wireMock.stubFor(post(urlEqualTo("/search"))
                .withRequestBody(containing("key-1"))
                .willReturn(status(429).withBody("quota exceeded")));
        wireMock.stubFor(post(urlEqualTo("/search"))
                .withRequestBody(containing("key-2"))
                .willReturn(okJson("""
                        {"results":[{"title":"测试歌手","url":"https://example.com/singer","content":"代表作与近期动态"}]}
                        """)));
        WebSearchProperties properties = new WebSearchProperties();
        properties.getTavily().setUrl(wireMock.baseUrl() + "/search");
        properties.getTavily().setApiKeys(List.of("key-1", "key-2"));
        TavilyWebSearchClient client = new TavilyWebSearchClient(properties);

        WebSearchResult result = client.search(WebSearchRequest.builder()
                .query("测试歌手是谁")
                .maxResults(3)
                .build());

        assertEquals("tavily", result.getProvider());
        assertTrue(result.hasDocuments());
        assertEquals("测试歌手", result.getDocuments().get(0).getTitle());
        wireMock.verify(postRequestedFor(urlEqualTo("/search")).withRequestBody(containing("key-1")));
        wireMock.verify(postRequestedFor(urlEqualTo("/search")).withRequestBody(containing("key-2")));
    }
}
