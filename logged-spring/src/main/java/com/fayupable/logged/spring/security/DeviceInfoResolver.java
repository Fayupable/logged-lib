package com.fayupable.logged.spring.security;

import java.util.List;
import java.util.Locale;

/**
 * Turns a {@code User-Agent} header into a coarse {@code "os / browser"}
 * description such as {@code "Windows / Chrome 129"}, for
 * {@code @Logged(includeDevice = true)}.
 *
 * <p>The raw header is never returned. Every result is assembled only from
 * a fixed set of names and at most {@value #MAX_VERSION_DIGITS} digits of a
 * major version, so nothing the client controls (including CR/LF or fake
 * log text) can ever reach a log line. Only major versions are kept: IP
 * address plus browser plus full version comes close to a device
 * fingerprint, which is personal data under GDPR / KVKK.
 *
 * <p>Detection order matters: Edge and Opera user agents also contain
 * {@code Chrome/}, and Chrome's also contains {@code Safari/}, so the more
 * specific tokens are checked first. Known limitation: iPadOS in desktop
 * mode reports itself as macOS and is reported as such; no attempt is made
 * to guess otherwise.
 *
 * <p>This class never throws: it only uses {@code contains}/{@code indexOf}
 * lookups and bounds-checked substrings, so there is no failure path to
 * guard. Any input it cannot make sense of resolves to {@value #UNKNOWN}.
 */
public final class DeviceInfoResolver {

    /** Placeholder for a missing, blank or unrecognized user agent. */
    public static final String UNKNOWN = "unknown";

    static final int MAX_INPUT_LENGTH = 512;
    static final int MAX_VERSION_DIGITS = 4;

    private static final String SEPARATOR = " / ";

    /**
     * Browser tokens in detection order: the first match wins. Edge, Opera
     * and Samsung Internet come before Chrome because their user agents also
     * contain {@code Chrome/}; the iOS variants map to the browser they are.
     */
    private static final List<BrowserRule> BROWSER_RULES = List.of(
            new BrowserRule("EdgiOS/", "Edge"),
            new BrowserRule("EdgA/", "Edge"),
            new BrowserRule("Edg/", "Edge"),
            new BrowserRule("Edge/", "Edge"),
            new BrowserRule("OPR/", "Opera"),
            new BrowserRule("SamsungBrowser/", "Samsung Internet"),
            new BrowserRule("FxiOS/", "Firefox"),
            new BrowserRule("Firefox/", "Firefox"),
            new BrowserRule("CriOS/", "Chrome"),
            new BrowserRule("Chrome/", "Chrome"));

    private DeviceInfoResolver() {
    }

    /**
     * Resolves a coarse device description from a {@code User-Agent} header.
     *
     * @param userAgent the raw header value, may be {@code null}
     * @return a value such as {@code "iOS 17 / Safari"}, {@code "curl"},
     *         {@code "Bot"}, or {@value #UNKNOWN}; never {@code null}
     */
    public static String resolve(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return UNKNOWN;
        }
        String ua = userAgent.length() > MAX_INPUT_LENGTH ? userAgent.substring(0, MAX_INPUT_LENGTH) : userAgent;

        String tool = resolveTool(ua);
        if (tool != null) {
            return tool;
        }

        String os = resolveOs(ua);
        String browser = resolveBrowser(ua);
        if (os == null && browser == null) {
            return UNKNOWN;
        }
        return (os != null ? os : UNKNOWN) + SEPARATOR + (browser != null ? browser : UNKNOWN);
    }

    private static String resolveTool(String ua) {
        String lower = ua.toLowerCase(Locale.ROOT);
        if (lower.startsWith("curl/")) {
            return "curl";
        }
        if (lower.contains("postmanruntime/")) {
            return "Postman";
        }
        // "bot/" and "bot;" rather than a bare "bot", so device models such as
        // "CUBOT" in an ordinary Android user agent are not mistaken for crawlers.
        if (lower.contains("bot/") || lower.contains("bot;") || lower.contains("crawler") || lower.contains("spider")) {
            return "Bot";
        }
        return null;
    }

    private static String resolveOs(String ua) {
        if (ua.contains("iPhone") || ua.contains("iPad") || ua.contains("iPod")) {
            return withMajor("iOS", majorAfter(ua, " OS "));
        }
        if (ua.contains("Android")) {
            return withMajor("Android", majorAfter(ua, "Android "));
        }
        if (ua.contains("Windows")) {
            return "Windows";
        }
        if (ua.contains("Macintosh") || ua.contains("Mac OS X")) {
            return "macOS";
        }
        if (ua.contains("Linux")) {
            return "Linux";
        }
        return null;
    }

    private static String resolveBrowser(String ua) {
        for (BrowserRule rule : BROWSER_RULES) {
            if (ua.contains(rule.token())) {
                return withMajor(rule.label(), majorAfter(ua, rule.token()));
            }
        }
        if (ua.contains("Safari/") && ua.contains("Version/")) {
            return "Safari";
        }
        return null;
    }

    /**
     * Reads the leading digits right after {@code token}, capped at
     * {@link #MAX_VERSION_DIGITS}. Returns an empty string when there are
     * none, so the result only ever contains ASCII digits.
     */
    private static String majorAfter(String ua, String token) {
        int start = ua.indexOf(token);
        if (start < 0) {
            return "";
        }
        int from = start + token.length();
        int end = from;
        while (end < ua.length() && end - from < MAX_VERSION_DIGITS && isAsciiDigit(ua.charAt(end))) {
            end++;
        }
        return ua.substring(from, end);
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static String withMajor(String name, String major) {
        return major.isEmpty() ? name : name + " " + major;
    }

    private record BrowserRule(String token, String label) {
    }
}
