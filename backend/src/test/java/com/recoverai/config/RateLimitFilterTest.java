package com.recoverai.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct unit test of {@link RateLimitFilter} - no Spring context needed,
 * so this exercises the exact same code the {@code @Profile("!test")}
 * bean would run in a real deployment, without needing to disable that
 * profile guard (see the filter's javadoc for why it opts out of the
 * "test" profile in the first place).
 */
class RateLimitFilterTest {

    private RateLimitProperties properties;
    private RateLimitFilter filter;

    @BeforeEach
    void setUp() {
        properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.setRequestsPerWindow(3);
        properties.setWindowSeconds(60);
        filter = new RateLimitFilter(properties);
    }

    private MockHttpServletRequest guardedRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/recovery-agent/evaluate-all");
        request.setRemoteAddr("203.0.113.5");
        return request;
    }

    @Test
    void underLimit_requestsPassThrough() throws Exception {
        for (int i = 0; i < 3; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(guardedRequest(), response, chain);

            assertThat(chain.getRequest()).isNotNull();
            assertThat(response.getStatus()).isNotEqualTo(429);
        }
    }

    @Test
    void overLimit_returnsTooManyRequests_andBlocksTheChain() throws Exception {
        for (int i = 0; i < 3; i++) {
            filter.doFilter(guardedRequest(), new MockHttpServletResponse(), new MockFilterChain());
        }

        MockHttpServletResponse blocked = new MockHttpServletResponse();
        MockFilterChain blockedChain = new MockFilterChain();
        filter.doFilter(guardedRequest(), blocked, blockedChain);

        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isEqualTo("60");
        assertThat(blocked.getContentAsString()).contains("Too many requests");
        assertThat(blockedChain.getRequest()).isNull();
    }

    @Test
    void differentClients_areTrackedIndependently() throws Exception {
        for (int i = 0; i < 3; i++) {
            filter.doFilter(guardedRequest(), new MockHttpServletResponse(), new MockFilterChain());
        }

        MockHttpServletRequest otherClient = new MockHttpServletRequest("POST", "/api/recovery-agent/evaluate-all");
        otherClient.setRemoteAddr("198.51.100.9");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(otherClient, response, new MockFilterChain());

        assertThat(response.getStatus()).isNotEqualTo(429);
    }

    @Test
    void unguardedPath_isNeverLimited() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/health");
        request.setRemoteAddr("203.0.113.5");

        for (int i = 0; i < 10; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            assertThat(response.getStatus()).isNotEqualTo(429);
        }
    }

    @Test
    void executeEndpoint_isGuarded() throws Exception {
        String path = "/api/recovery/" + UUID.randomUUID() + "/execute";
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
            request.setRemoteAddr("203.0.113.5");
            filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        }

        MockHttpServletRequest fourth = new MockHttpServletRequest("POST", path);
        fourth.setRemoteAddr("203.0.113.5");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(fourth, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
    }

    @Test
    void disabled_neverLimitsRegardlessOfVolume() throws Exception {
        properties.setEnabled(false);

        for (int i = 0; i < 10; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(guardedRequest(), response, new MockFilterChain());
            assertThat(response.getStatus()).isNotEqualTo(429);
        }
    }

    @Test
    void xForwardedFor_isUsedAsClientKey_overRemoteAddr() throws Exception {
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest request = guardedRequest();
            request.setRemoteAddr("10.0.0.1"); // same proxy remoteAddr for every request
            request.addHeader("X-Forwarded-For", "203.0.113.99, 10.0.0.1");
            filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        }

        MockHttpServletRequest differentRealClient = guardedRequest();
        differentRealClient.setRemoteAddr("10.0.0.1");
        differentRealClient.addHeader("X-Forwarded-For", "203.0.113.100, 10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(differentRealClient, response, new MockFilterChain());

        assertThat(response.getStatus()).isNotEqualTo(429);
    }
}
