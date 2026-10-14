package com.fayupable.logged.spring.security;

/**
 * Masks e-mail-like values for {@link com.fayupable.logged.core.annotation.CallerMode#MASKED}.
 *
 * <p>A value is treated as an e-mail address only when it contains exactly
 * one {@code @} with a non-empty part on both sides. Such a value keeps the
 * first character of its local part and its whole domain, for example
 * {@code jane.doe@example.com} becomes {@code j***@example.com}: enough to
 * tell tenants or identity providers apart while troubleshooting, without
 * identifying the person. Anything else (a numeric user id, a plain
 * username) is returned unchanged.
 */
public final class EmailMasker {

    private static final String MASK = "***";

    private EmailMasker() {
    }

    /**
     * Masks {@code value} if it looks like an e-mail address.
     *
     * @param value the value to mask, may be {@code null}
     * @return the masked value, or {@code value} itself if it is not
     *         e-mail-like
     */
    public static String mask(String value) {
        if (value == null) {
            return null;
        }
        int at = value.indexOf('@');
        boolean emailLike = at > 0 && at < value.length() - 1 && value.indexOf('@', at + 1) < 0;
        if (!emailLike) {
            return value;
        }
        return value.charAt(0) + MASK + value.substring(at);
    }
}
