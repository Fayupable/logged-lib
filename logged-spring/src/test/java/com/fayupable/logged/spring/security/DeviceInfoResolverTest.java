package com.fayupable.logged.spring.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DeviceInfoResolver")
class DeviceInfoResolverTest {

    /** Everything the resolver may ever return: fixed names, digits, spaces and the separator. */
    private static final Pattern ALLOWED_OUTPUT = Pattern.compile(
            "unknown|curl|Postman|Bot|"
                    + "(iOS|Android|Windows|macOS|Linux|unknown)( \\d{1,4})? / "
                    + "(Edge|Opera|Samsung Internet|Firefox|Chrome|Safari|unknown)( \\d{1,4})?");

    private static String resolve(String userAgent) {
        String resolved = DeviceInfoResolver.resolve(userAgent);
        assertThat(resolved).matches(ALLOWED_OUTPUT);
        return resolved;
    }

    @Nested
    @DisplayName("desktop browsers")
    class Desktop {

        @ParameterizedTest(name = "{1}")
        @CsvSource(delimiter = '|', value = {
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.6668.90 Safari/537.36"
                        + "|Windows / Chrome 129",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Safari/537.36 Edg/129.0.2792.79"
                        + "|Windows / Edge 129",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36 OPR/114.0.0.0"
                        + "|Windows / Opera 114",
                "Mozilla/5.0 (X11; Linux x86_64; rv:131.0) Gecko/20100101 Firefox/131.0"
                        + "|Linux / Firefox 131",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15"
                        + "|macOS / Safari",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/70.0.3538.102 Safari/537.36 Edge/18.19045"
                        + "|Windows / Edge 18"
        })
        @DisplayName("resolve to OS and browser with major version only")
        void resolvesDesktopBrowsers(String userAgent, String expected) {
            assertThat(resolve(userAgent)).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("mobile browsers")
    class Mobile {

        @ParameterizedTest(name = "{1}")
        @CsvSource(delimiter = '|', value = {
                "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1"
                        + "|iOS 17 / Safari",
                "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/129.0.6668.69 Mobile/15E148 Safari/604.1"
                        + "|iOS 17 / Chrome 129",
                "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) FxiOS/131.0 Mobile/15E148 Safari/605.1.15"
                        + "|iOS 17 / Firefox 131",
                "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 EdgiOS/129.2792.84 Mobile/15E148 Safari/605.1.15"
                        + "|iOS 17 / Edge 129",
                "Mozilla/5.0 (iPad; CPU OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"
                        + "|iOS 16 / Safari",
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.6668.81 Mobile Safari/537.36"
                        + "|Android 14 / Chrome 129",
                "Mozilla/5.0 (Android 14; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0"
                        + "|Android 14 / Firefox 128",
                "Mozilla/5.0 (Linux; Android 14; SM-S921B) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/26.0 Chrome/122.0.0.0 Mobile Safari/537.36"
                        + "|Android 14 / Samsung Internet 26",
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36 EdgA/129.0.2792.84"
                        + "|Android 14 / Edge 129",
                "Mozilla/5.0 (Linux; Android 13; CUBOT KINGKONG 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36"
                        + "|Android 13 / Chrome 129"
        })
        @DisplayName("resolve iOS and Android browsers, including CriOS, FxiOS, EdgiOS and Samsung Internet")
        void resolvesMobileBrowsers(String userAgent, String expected) {
            assertThat(resolve(userAgent)).isEqualTo(expected);
        }

        @Test
        @DisplayName("reports iPadOS in desktop mode as macOS (documented limitation)")
        void reportsIpadDesktopModeAsMacOs() {
            String ipadDesktopMode = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 "
                    + "(KHTML, like Gecko) Version/17.4 Safari/605.1.15";

            assertThat(resolve(ipadDesktopMode)).isEqualTo("macOS / Safari");
        }
    }

    @Nested
    @DisplayName("tools and bots")
    class ToolsAndBots {

        @ParameterizedTest(name = "{1}")
        @CsvSource(delimiter = '|', value = {
                "curl/8.7.1|curl",
                "PostmanRuntime/7.42.0|Postman",
                "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)|Bot",
                "Mozilla/5.0 (Linux; Android 6.0.1; Nexus 5X Build/MMB29P) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.6668.89 Mobile Safari/537.36 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)|Bot",
                "Mozilla/5.0 (compatible; bingbot/2.0; +http://www.bing.com/bingbot.htm)|Bot",
                "Mozilla/5.0 (compatible; Baiduspider/2.0; +http://www.baidu.com/search/spider.html)|Bot",
                "SomeCrawler 1.0|Bot"
        })
        @DisplayName("resolve to a fixed tool name or Bot, never to the tool's own text")
        void resolvesToolsAndBots(String userAgent, String expected) {
            assertThat(resolve(userAgent)).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("missing or unusual input")
    class UnusualInput {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t", "something completely different", "Mozilla/5.0"})
        @DisplayName("resolves to unknown when nothing can be recognized")
        void resolvesUnknown(String userAgent) {
            assertThat(resolve(userAgent)).isEqualTo(DeviceInfoResolver.UNKNOWN);
        }

        @Test
        @DisplayName("keeps a known OS when the browser is unknown, and vice versa")
        void keepsKnownHalf() {
            assertThat(resolve("Mozilla/5.0 (Windows NT 10.0; Win64; x64) SomeApp/1.0")).isEqualTo("Windows / unknown");
            assertThat(resolve("Firefox/131.0")).isEqualTo("unknown / Firefox 131");
        }

        @Test
        @DisplayName("keeps the label without a version when the version is missing or not numeric")
        void keepsLabelWithoutVersion() {
            assertThat(resolve("Mozilla/5.0 (Windows NT 10.0) Chrome/")).isEqualTo("Windows / Chrome");
            assertThat(resolve("Mozilla/5.0 (Windows NT 10.0) Chrome/abc")).isEqualTo("Windows / Chrome");
            assertThat(resolve("Mozilla/5.0 (Linux; Android x) Firefox/131.0")).isEqualTo("Android / Firefox 131");
        }

        @Test
        @DisplayName("reads every digit of a major version, including 0 and 9")
        void readsZeroAndNine() {
            assertThat(resolve("Mozilla/5.0 (Windows NT 10.0) Chrome/109.0")).isEqualTo("Windows / Chrome 109");
            assertThat(resolve("Mozilla/5.0 (Windows NT 10.0) Chrome/90.0")).isEqualTo("Windows / Chrome 90");
            assertThat(resolve("Mozilla/5.0 (Windows NT 10.0) Chrome/99.0")).isEqualTo("Windows / Chrome 99");
        }

        @Test
        @DisplayName("treats exactly 512 characters as within the cap and the 513th as beyond it")
        void inputCapBoundary() {
            String prefix = "Mozilla/5.0 (Windows NT 10.0) ";
            String fillTo511 = "x".repeat(DeviceInfoResolver.MAX_INPUT_LENGTH - prefix.length() - "Chrome/1".length());

            String endsAtCap = prefix + fillTo511 + "Chrome/1";
            String endsPastCap = prefix + fillTo511 + "xChrome/1";

            assertThat(endsAtCap).hasSize(DeviceInfoResolver.MAX_INPUT_LENGTH);
            assertThat(resolve(endsAtCap)).isEqualTo("Windows / Chrome 1");
            assertThat(resolve(endsPastCap)).isEqualTo("Windows / Chrome");
        }

        @Test
        @DisplayName("caps a major version at four digits")
        void capsMajorVersion() {
            assertThat(resolve("Mozilla/5.0 (Windows NT 10.0) Chrome/1234567890.0")).isEqualTo("Windows / Chrome 1234");
        }

        @Test
        @DisplayName("only reads the first 512 characters of a huge header")
        void ignoresTokensBeyondTheInputCap() {
            String padding = "x".repeat(10 * 1024);

            assertThat(resolve("Mozilla/5.0 (Windows NT 10.0) Chrome/129.0 " + padding)).isEqualTo("Windows / Chrome 129");
            assertThat(resolve(padding + " (Windows NT 10.0) Chrome/129.0")).isEqualTo(DeviceInfoResolver.UNKNOWN);
        }

        @Test
        @DisplayName("never lets CR/LF or fake log text from the header into the result")
        void neverEchoesHeaderContent() {
            String injected = "Mozilla/5.0 (Windows NT 10.0) Chrome/129\r\n"
                    + "2026-10-15 INFO  Slf4jInvocationEventEmitter - [user:admin] AdminService.deleteAll completed";

            String resolved = resolve(injected);

            assertThat(resolved).isEqualTo("Windows / Chrome 129");
            assertThat(resolved).doesNotContain("\r", "\n", "admin", "INFO");
        }

        @Test
        @DisplayName("does not mistake non-ASCII digits for a version")
        void ignoresNonAsciiDigits() {
            assertThat(resolve("Mozilla/5.0 (Windows NT 10.0) Chrome/١٢٩")).isEqualTo("Windows / Chrome");
        }
    }
}
