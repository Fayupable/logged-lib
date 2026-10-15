package com.fayupable.logged.spring.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the current request's coarse device description for the
 * {@link com.fayupable.logged.core.port.IClientInfoPort} implementations in
 * this package, the device-info counterpart of {@link RequestClientIpResolver}.
 *
 * <p>Reading the header is kept here and parsing in {@link DeviceInfoResolver},
 * so the parser stays a pure function with no servlet dependency.
 */
final class RequestDeviceResolver {

    private static final String USER_AGENT_HEADER = "User-Agent";

    private RequestDeviceResolver() {
    }

    /**
     * @return a coarse device description such as {@code "Windows / Chrome 129"},
     *         or {@value DeviceInfoResolver#UNKNOWN} when there is no current
     *         request, no {@code User-Agent} header, or nothing recognizable
     *         in it; never {@code null}
     */
    static String resolveDevice() {
        HttpServletRequest request = CurrentRequest.get();
        return DeviceInfoResolver.resolve(request != null ? request.getHeader(USER_AGENT_HEADER) : null);
    }
}
