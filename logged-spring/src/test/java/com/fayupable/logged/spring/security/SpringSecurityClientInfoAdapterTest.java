package com.fayupable.logged.spring.security;

import com.fayupable.logged.core.annotation.CallerMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
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

    @Nested
    @DisplayName("caller modes")
    class CallerModes {

        private static final String EMAIL = "jane.doe@example.com";
        private static final CallerIdentityResolver ID_RESOLVER = authentication -> "42";

        private SpringSecurityClientInfoAdapter adapterWith(CallerMode configured, CallerIdentityResolver resolver) {
            return new SpringSecurityClientInfoAdapter(false, configured, resolver);
        }

        @Test
        @DisplayName("NAME (the default) writes an e-mail username in full")
        void nameWritesEmailInFull() {
            authenticateAs(EMAIL);

            assertThat(adapter.resolveCallerIdentity()).isEqualTo("user:" + EMAIL);
            assertThat(adapter.resolveCallerIdentity(CallerMode.DEFAULT)).isEqualTo("user:" + EMAIL);
        }

        @Test
        @DisplayName("a null or DEFAULT configured mode behaves as NAME")
        void nullOrDefaultConfiguredModeBehavesAsName() {
            authenticateAs(EMAIL);

            assertThat(adapterWith(null, null).resolveCallerIdentity()).isEqualTo("user:" + EMAIL);
            assertThat(adapterWith(CallerMode.DEFAULT, null).resolveCallerIdentity()).isEqualTo("user:" + EMAIL);
        }

        @Test
        @DisplayName("configured MASKED masks an e-mail username")
        void maskedMasksEmail() {
            authenticateAs(EMAIL);

            assertThat(adapterWith(CallerMode.MASKED, null).resolveCallerIdentity()).isEqualTo("user:j***@example.com");
        }

        @Test
        @DisplayName("configured MASKED leaves a username that is not an e-mail unchanged")
        void maskedLeavesNonEmailUnchanged() {
            authenticateAs("42");

            assertThat(adapterWith(CallerMode.MASKED, null).resolveCallerIdentity()).isEqualTo("user:42");
        }

        @Test
        @DisplayName("configured MASKED does not affect the IP fallback for anonymous callers")
        void maskedKeepsIpFallbackForAnonymous() {
            authenticateAsAnonymous();
            withRequestFrom("198.51.100.7");

            assertThat(adapterWith(CallerMode.MASKED, null).resolveCallerIdentity()).isEqualTo("ip:198.51.100.7");
        }

        @Test
        @DisplayName("configured CUSTOM uses the resolver's value")
        void customUsesResolver() {
            authenticateAs(EMAIL);

            assertThat(adapterWith(CallerMode.CUSTOM, ID_RESOLVER).resolveCallerIdentity()).isEqualTo("user:42");
        }

        @Test
        @DisplayName("CUSTOM never calls the resolver for an anonymous caller")
        void customSkipsResolverForAnonymous() {
            authenticateAsAnonymous();
            withRequestFrom("198.51.100.7");
            CallerIdentityResolver failing = authentication -> {
                throw new AssertionError("resolver must not be called");
            };

            assertThat(adapterWith(CallerMode.CUSTOM, failing).resolveCallerIdentity()).isEqualTo("ip:198.51.100.7");
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"", "  "})
        @DisplayName("CUSTOM falls back to the IP when the resolver returns null or blank")
        void customFallsBackToIpOnEmptyResolverResult(String resolved) {
            authenticateAs(EMAIL);
            withRequestFrom("203.0.113.5");

            assertThat(adapterWith(CallerMode.CUSTOM, authentication -> resolved).resolveCallerIdentity())
                    .isEqualTo("ip:203.0.113.5");
        }

        @Test
        @DisplayName("CUSTOM without a resolver masks instead of writing the name in full")
        void customWithoutResolverMasks() {
            authenticateAs(EMAIL);

            assertThat(adapter.resolveCallerIdentity(CallerMode.CUSTOM)).isEqualTo("user:j***@example.com");
        }

        @Test
        @DisplayName("a method-level mode overrides the configured mode in both directions")
        void methodModeOverridesConfiguredMode() {
            authenticateAs(EMAIL);
            SpringSecurityClientInfoAdapter masking = adapterWith(CallerMode.MASKED, ID_RESOLVER);

            assertThat(masking.resolveCallerIdentity(CallerMode.DEFAULT)).isEqualTo("user:j***@example.com");
            assertThat(masking.resolveCallerIdentity(CallerMode.NAME)).isEqualTo("user:" + EMAIL);
            assertThat(masking.resolveCallerIdentity(CallerMode.CUSTOM)).isEqualTo("user:42");
            assertThat(adapter.resolveCallerIdentity(CallerMode.MASKED)).isEqualTo("user:j***@example.com");
        }

        @Test
        @DisplayName("a null method-level mode behaves as DEFAULT")
        void nullMethodModeBehavesAsDefault() {
            authenticateAs(EMAIL);

            assertThat(adapterWith(CallerMode.MASKED, null).resolveCallerIdentity(null)).isEqualTo("user:j***@example.com");
        }
    }

    @Nested
    @DisplayName("resolveCallerDevice")
    class ResolveCallerDevice {

        private final SpringSecurityClientInfoAdapter adapter = new SpringSecurityClientInfoAdapter(false);

        private void withUserAgent(String userAgent) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("203.0.113.5");
            if (userAgent != null) {
                request.addHeader("User-Agent", userAgent);
            }
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        }

        @Test
        @DisplayName("resolves a coarse OS / browser value from the User-Agent header")
        void resolvesCoarseDevice() {
            withUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.6668.90 Safari/537.36");

            assertThat(adapter.resolveCallerDevice()).isEqualTo("Windows / Chrome 129");
        }

        @Test
        @DisplayName("resolves to unknown when the request has no User-Agent header")
        void resolvesUnknownWithoutHeader() {
            withUserAgent(null);

            assertThat(adapter.resolveCallerDevice()).isEqualTo("unknown");
        }

        @Test
        @DisplayName("resolves to unknown when there is no active HTTP request")
        void resolvesUnknownWithoutRequest() {
            assertThat(adapter.resolveCallerDevice()).isEqualTo("unknown");
        }

        @Test
        @DisplayName("never returns the raw header, even when it carries injected log text")
        void neverReturnsRawHeader() {
            withUserAgent("Mozilla/5.0 (Windows NT 10.0) Chrome/129\r\nFAKE LOG LINE [user:admin]");

            assertThat(adapter.resolveCallerDevice()).isEqualTo("Windows / Chrome 129");
        }
    }

    @Nested
    @DisplayName("with X-Forwarded-For trust enabled and a malformed header")
    class WithMalformedForwardedHeader {

        private final SpringSecurityClientInfoAdapter trustingAdapter = new SpringSecurityClientInfoAdapter(true);

        @ParameterizedTest
        @ValueSource(strings = {
                "198.51.100.99\r\nFAKE LOG LINE",
                "unknown",
                "198.51.100.99:8080x",
                "[2001:db8::1]:443",
                "fe80::1%eth0",
                "<script>",
                "1111:2222:3333:4444:5555:6666:7777:8888:9999:aaaa"
        })
        @DisplayName("ignores an entry that is not shaped like an IP and uses the remote address")
        void ignoresMalformedEntry(String forwardedFor) {
            withRequestFrom("203.0.113.5", forwardedFor);

            assertThat(trustingAdapter.resolveCallerIdentity()).isEqualTo("ip:203.0.113.5");
            assertThat(trustingAdapter.resolveCallerIp()).isEqualTo("203.0.113.5");
        }

        @ParameterizedTest
        @ValueSource(strings = {"198.51.100.99", "2001:db8::1", "2001:DB8::ABCD", "::ffff:198.51.100.99", "ffff:FFFF::9", "a0:A0::90"})
        @DisplayName("still accepts IPv4, IPv6 and IPv4-mapped entries")
        void acceptsWellFormedEntries(String forwardedFor) {
            withRequestFrom("203.0.113.5", forwardedFor + ", 10.0.0.1");

            assertThat(trustingAdapter.resolveCallerIp()).isEqualTo(forwardedFor);
        }

        @Test
        @DisplayName("accepts an entry of exactly the maximum IPv6 length")
        void acceptsMaximumLength() {
            String longestIpv6 = "0000:0000:0000:0000:0000:ffff:255.255.255.255";
            withRequestFrom("203.0.113.5", longestIpv6);

            assertThat(longestIpv6).hasSize(45);
            assertThat(trustingAdapter.resolveCallerIp()).isEqualTo(longestIpv6);
        }

        @Test
        @DisplayName("falls back to the remote address when the first entry is empty")
        void ignoresEmptyFirstEntry() {
            withRequestFrom("203.0.113.5", " , 198.51.100.99");

            assertThat(trustingAdapter.resolveCallerIp()).isEqualTo("203.0.113.5");
        }
    }
}
