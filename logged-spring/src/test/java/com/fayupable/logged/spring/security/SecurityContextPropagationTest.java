package com.fayupable.logged.spring.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SecurityContextPropagation")
class SecurityContextPropagationTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private SecurityContext contextAuthenticatedAs(String username) {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(username, "password"));
        return context;
    }

    @Nested
    @DisplayName("capture()")
    class Capture {

        @Test
        @DisplayName("never returns null, even when nothing was ever set on this thread")
        void neverReturnsNull() {
            assertThat(SecurityContextPropagation.capture()).isNotNull();
        }

        @Test
        @DisplayName("returns the currently active security context")
        void returnsActiveContext() {
            SecurityContext context = contextAuthenticatedAs("alice");
            SecurityContextHolder.setContext(context);

            assertThat(SecurityContextPropagation.capture()).isSameAs(context);
        }
    }

    @Nested
    @DisplayName("adopt()")
    class Adopt {

        @Test
        @DisplayName("makes the given security context active on this thread")
        void makesContextActive() {
            SecurityContext context = contextAuthenticatedAs("alice");

            Runnable restore = SecurityContextPropagation.adopt(context);
            try {
                assertThat(SecurityContextPropagation.capture()).isSameAs(context);
            } finally {
                restore.run();
            }
        }

        @Test
        @DisplayName("restores the previously active context once the returned Runnable is run")
        void restoresPreviousContextOnRun() {
            SecurityContext original = contextAuthenticatedAs("alice");
            SecurityContextHolder.setContext(original);

            Runnable restore = SecurityContextPropagation.adopt(contextAuthenticatedAs("bob"));
            restore.run();

            assertThat(SecurityContextPropagation.capture()).isSameAs(original);
        }

        @Test
        @DisplayName("clears the context when adopting null")
        void clearsContextWhenAdoptingNull() {
            SecurityContextHolder.setContext(contextAuthenticatedAs("alice"));

            Runnable restore = SecurityContextPropagation.adopt(null);
            try {
                assertThat(SecurityContextPropagation.capture().getAuthentication()).isNull();
            } finally {
                restore.run();
            }
        }
    }
}
