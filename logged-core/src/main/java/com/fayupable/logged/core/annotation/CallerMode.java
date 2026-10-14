package com.fayupable.logged.core.annotation;

/**
 * Controls how an authenticated caller's identity is written into an
 * invocation event, for applications where the authenticated name is
 * personal data (for example an e-mail address used as the username, which
 * is personal data under GDPR / KVKK).
 *
 * <p>Only the authenticated-principal tier of caller identity is affected.
 * The IP fallback used for unauthenticated callers and the
 * {@code "unknown"} placeholder are never changed by this setting.
 */
public enum CallerMode {

    /**
     * Use the application-wide setting (for example
     * {@code logged.client-info.caller} in logged-spring). This is the
     * default value of {@link Logged#caller()}, so a method that does not
     * set it inherits the global behavior.
     */
    DEFAULT,

    /**
     * Write the authenticated name as is, for example
     * {@code user:jane.doe@example.com}. This is the library's historical
     * behavior.
     */
    NAME,

    /**
     * Mask the authenticated name when it looks like an e-mail address,
     * keeping only the first character of the local part and the whole
     * domain, for example {@code user:j***@example.com}. Names that are not
     * e-mail addresses (a numeric user id, a plain username) are written
     * unchanged.
     */
    MASKED,

    /**
     * Use an application-provided resolver (for example logged-spring's
     * {@code CallerIdentityResolver} bean) to produce the identity, typically
     * an internal user id instead of the login name.
     */
    CUSTOM
}
