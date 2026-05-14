package org.javaup.ai.ai.function.call;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.javaup.ai.vo.UserDetailVo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;

class UserCallWireMockTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @Test
    void shouldForwardTokenHeaderToCurrentUserEndpoint() {
        wireMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/damai/user/user/current"))
                .withHeader("token", equalTo("token-test"))
                .willReturn(okJson("""
                        {"code":0,"data":{"id":1001,"name":"演示用户","mobile":"13800138000","email":"demo@test.com"}}
                        """)));

        UserCall userCall = new UserCall() {
            @Override
            protected String currentUserUrl() {
                return wireMock.baseUrl() + "/damai/user/user/current";
            }
        };
        ReflectionTestUtils.setField(userCall, "requestAuthSupport", new DaMaiRequestAuthSupport());

        UserDetailVo user = userCall.currentUser("token-test");

        assertEquals(1001L, user.getId());
        assertEquals("13800138000", user.getMobile());
        wireMock.verify(postRequestedFor(urlEqualTo("/damai/user/user/current"))
                .withHeader("token", equalTo("token-test")));
    }
}
