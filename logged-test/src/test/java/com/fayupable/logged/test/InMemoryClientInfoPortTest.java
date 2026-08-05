package com.fayupable.logged.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InMemoryClientInfoPort")
class InMemoryClientInfoPortTest {

    @Nested
    @DisplayName("default construction")
    class DefaultConstruction {

        @Test
        @DisplayName("reports the caller as unknown")
        void reportsUnknownCaller() {
            InMemoryClientInfoPort clientInfoPort = new InMemoryClientInfoPort();

            assertThat(clientInfoPort.resolveCallerIdentity()).isEqualTo("unknown");
        }
    }

    @Nested
    @DisplayName("construction with a fixed identity")
    class ConstructionWithFixedIdentity {

        @Test
        @DisplayName("reports the given caller identity")
        void reportsGivenCallerIdentity() {
            InMemoryClientInfoPort clientInfoPort = new InMemoryClientInfoPort("user:42");

            assertThat(clientInfoPort.resolveCallerIdentity()).isEqualTo("user:42");
        }
    }

    @Nested
    @DisplayName("setCallerIdentity")
    class SetCallerIdentity {

        @Test
        @DisplayName("changes the identity reported by subsequent calls")
        void changesReportedIdentity() {
            InMemoryClientInfoPort clientInfoPort = new InMemoryClientInfoPort("user:42");

            clientInfoPort.setCallerIdentity("user:99");

            assertThat(clientInfoPort.resolveCallerIdentity()).isEqualTo("user:99");
        }
    }
}
