package com.fayupable.logged.core.port;

import com.fayupable.logged.core.annotation.CallerMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("IClientInfoPort")
class IClientInfoPortTest {

    @ParameterizedTest
    @EnumSource(CallerMode.class)
    @DisplayName("resolveCallerIdentity(mode) defaults to resolveCallerIdentity() for implementations without mode support")
    void modeOverloadDelegatesByDefault(CallerMode mode) {
        IClientInfoPort port = () -> "user:42";

        assertThat(port.resolveCallerIdentity(mode)).isEqualTo("user:42");
    }
}
