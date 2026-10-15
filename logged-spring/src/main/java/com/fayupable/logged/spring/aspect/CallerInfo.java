package com.fayupable.logged.spring.aspect;

/**
 * Everything {@link LoggedAspect} resolves about the caller of one
 * invocation, captured together on the calling thread so it can be carried
 * unchanged into a {@link java.util.concurrent.CompletableFuture} completion
 * callback running elsewhere.
 *
 * @param identity the caller identity, never {@code null}
 * @param ip       the caller's IP address, or {@code null} when not requested
 *                 or not resolvable
 * @param device   a coarse device description, or {@code null} when not
 *                 requested or not supported by the client info port
 */
record CallerInfo(String identity, String ip, String device) {
}
