package com.fayupable.logged.spring.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RequestAttributesPropagation")
class RequestAttributesPropagationTest {

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private RequestAttributes attributesFrom(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        return new ServletRequestAttributes(request);
    }

    @Nested
    @DisplayName("capture()")
    class Capture {

        @Test
        @DisplayName("returns null when there is no active request on this thread")
        void returnsNullWhenNothingActive() {
            assertThat(RequestAttributesPropagation.capture()).isNull();
        }

        @Test
        @DisplayName("returns the currently active request attributes")
        void returnsActiveAttributes() {
            RequestAttributes attributes = attributesFrom("203.0.113.5");
            RequestContextHolder.setRequestAttributes(attributes);

            assertThat(RequestAttributesPropagation.capture()).isSameAs(attributes);
        }
    }

    @Nested
    @DisplayName("adopt()")
    class Adopt {

        @Test
        @DisplayName("makes the given request attributes active on this thread")
        void makesAttributesActive() {
            RequestAttributes attributes = attributesFrom("203.0.113.5");

            Runnable restore = RequestAttributesPropagation.adopt(attributes);
            try {
                assertThat(RequestAttributesPropagation.capture()).isSameAs(attributes);
            } finally {
                restore.run();
            }
        }

        @Test
        @DisplayName("restores the previously active attributes once the returned Runnable is run")
        void restoresPreviousAttributesOnRun() {
            RequestAttributes original = attributesFrom("203.0.113.5");
            RequestContextHolder.setRequestAttributes(original);

            Runnable restore = RequestAttributesPropagation.adopt(attributesFrom("198.51.100.9"));
            restore.run();

            assertThat(RequestAttributesPropagation.capture()).isSameAs(original);
        }

        @Test
        @DisplayName("clears this thread's attributes once the returned Runnable is run, when nothing was active before")
        void clearsAttributesOnRunWhenNothingWasActiveBefore() {
            Runnable restore = RequestAttributesPropagation.adopt(attributesFrom("198.51.100.9"));
            restore.run();

            assertThat(RequestAttributesPropagation.capture()).isNull();
        }

        @Test
        @DisplayName("clears any active attributes when adopting null")
        void clearsAttributesWhenAdoptingNull() {
            RequestContextHolder.setRequestAttributes(attributesFrom("203.0.113.5"));

            Runnable restore = RequestAttributesPropagation.adopt(null);
            try {
                assertThat(RequestAttributesPropagation.capture()).isNull();
            } finally {
                restore.run();
            }
        }
    }
}
