package com.fayupable.logged.spring.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SpringSecurityClientInfoAdapter")
class SpringSecurityClientInfoAdapterTest {

    private final SpringSecurityClientInfoAdapter adapter = new SpringSecurityClientInfoAdapter(false);

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private void authenticateAs(String username) {
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, "password", authorities)
        );
    }

    private void authenticateAsAnonymous() {
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"));
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymousUser", authorities)
        );
    }

    private void withRequestFrom(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private void withRequestFrom(String remoteAddr, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        request.addHeader("X-Forwarded-For", forwardedFor);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Nested
    @DisplayName("with an authenticated principal")
    class AuthenticatedPrincipal {

        @Test
        @DisplayName("resolves the caller as the authenticated username")
        void resolvesAuthenticatedUsername() {
            authenticateAs("enis");

            String identity = adapter.resolveCallerIdentity();

            System.out.println("resolved identity = " + identity);

            assertThat(identity).isEqualTo("user:enis");
        }

        @Test
        @DisplayName("prefers the authenticated user over the request IP when both are present")
        void prefersAuthenticationOverIp() {
            authenticateAs("enis");
            withRequestFrom("203.0.113.5");

            assertThat(adapter.resolveCallerIdentity()).isEqualTo("user:enis");
        }
    }

    @Nested
    @DisplayName("with no authentication")
    class NoAuthentication {

        @Test
        @DisplayName("falls back to the request's remote address")
        void fallsBackToRemoteAddress() {
            withRequestFrom("203.0.113.5");

            String identity = adapter.resolveCallerIdentity();

            System.out.println("resolved identity = " + identity);

            assertThat(identity).isEqualTo("ip:203.0.113.5");
        }

        @Test
        @DisplayName("resolves to unknown when there is no active HTTP request")
        void resolvesUnknownWithoutRequest() {
            assertThat(adapter.resolveCallerIdentity()).isEqualTo("unknown");
        }
    }

    @Nested
    @DisplayName("with an anonymous authentication")
    class AnonymousAuthentication {

        @Test
        @DisplayName("treats anonymousUser as unauthenticated and falls back to the request IP")
        void treatsAnonymousAsUnauthenticated() {
            authenticateAsAnonymous();
            withRequestFrom("198.51.100.7");

            String identity = adapter.resolveCallerIdentity();

            System.out.println("resolved identity = " + identity);

            assertThat(identity).isEqualTo("ip:198.51.100.7");
        }

        @Test
        @DisplayName("resolves to unknown when anonymous and there is no active request")
        void resolvesUnknownWhenAnonymousWithoutRequest() {
            authenticateAsAnonymous();

            assertThat(adapter.resolveCallerIdentity()).isEqualTo("unknown");
        }
    }

    @Nested
    @DisplayName("with X-Forwarded-For trust disabled (the default)")
    class WithForwardedHeaderTrustDisabled {

        @Test
        @DisplayName("ignores X-Forwarded-For and uses the request's remote address instead")
        void ignoresForwardedForHeader() {
            withRequestFrom("203.0.113.5", "198.51.100.99");

            assertThat(adapter.resolveCallerIdentity()).isEqualTo("ip:203.0.113.5");
        }
    }

    @Nested
    @DisplayName("with X-Forwarded-For trust enabled")
    class WithForwardedHeaderTrustEnabled {

        private final SpringSecurityClientInfoAdapter trustingAdapter = new SpringSecurityClientInfoAdapter(true);

        @Test
        @DisplayName("uses the first X-Forwarded-For entry when present")
        void usesForwardedForHeader() {
            withRequestFrom("203.0.113.5", "198.51.100.99, 10.0.0.1");

            String identity = trustingAdapter.resolveCallerIdentity();

            System.out.println("resolved identity = " + identity);

            assertThat(identity).isEqualTo("ip:198.51.100.99");
        }

        @Test
        @DisplayName("falls back to the remote address when the header is absent")
        void fallsBackToRemoteAddressWithoutHeader() {
            withRequestFrom("203.0.113.5");

            assertThat(trustingAdapter.resolveCallerIdentity()).isEqualTo("ip:203.0.113.5");
        }
    }

    @Nested
    @DisplayName("resolveCallerIp")
    class ResolveCallerIp {

        @Test
        @DisplayName("resolves the raw IP address, unprefixed, even without authentication")
        void resolvesRawIpWithoutAuthentication() {
            withRequestFrom("203.0.113.5");

            assertThat(adapter.resolveCallerIp()).isEqualTo("203.0.113.5");
        }

        @Test
        @DisplayName("resolves the raw IP address even when the caller also has an authenticated identity")
        void resolvesRawIpAlongsideAuthenticatedIdentity() {
            authenticateAs("enis");
            withRequestFrom("203.0.113.5");

            assertThat(adapter.resolveCallerIp()).isEqualTo("203.0.113.5");
        }

        @Test
        @DisplayName("resolves to null when there is no active HTTP request")
        void resolvesNullWithoutRequest() {
            assertThat(adapter.resolveCallerIp()).isNull();
        }
    }
}