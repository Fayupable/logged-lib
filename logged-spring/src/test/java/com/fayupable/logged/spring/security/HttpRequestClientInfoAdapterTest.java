package com.fayupable.logged.spring.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HttpRequestClientInfoAdapter")
class HttpRequestClientInfoAdapterTest {

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void withRequestFrom(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Nested
    @DisplayName("without any Spring Security on the classpath")
    class NoAuthenticationInvolved {

        @Test
        @DisplayName("resolves the caller from the request's remote address")
        void resolvesFromRemoteAddress() {
            withRequestFrom("203.0.113.5");

            HttpRequestClientInfoAdapter adapter = new HttpRequestClientInfoAdapter(false);

            assertThat(adapter.resolveCallerIdentity()).isEqualTo("ip:203.0.113.5");
        }

        @Test
        @DisplayName("resolves to unknown when there is no active HTTP request")
        void resolvesUnknownWithoutRequest() {
            HttpRequestClientInfoAdapter adapter = new HttpRequestClientInfoAdapter(false);

            assertThat(adapter.resolveCallerIdentity()).isEqualTo("unknown");
        }
    }

    @Nested
    @DisplayName("with X-Forwarded-For trust enabled")
    class WithForwardedHeaderTrustEnabled {

        @Test
        @DisplayName("uses the first X-Forwarded-For entry when present")
        void usesForwardedForHeader() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("203.0.113.5");
            request.addHeader("X-Forwarded-For", "198.51.100.99, 10.0.0.1");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

            HttpRequestClientInfoAdapter adapter = new HttpRequestClientInfoAdapter(true);

            assertThat(adapter.resolveCallerIdentity()).isEqualTo("ip:198.51.100.99");
        }
    }
}
