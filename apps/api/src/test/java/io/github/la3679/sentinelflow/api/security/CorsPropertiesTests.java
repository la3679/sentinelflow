/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The origin list, checked at startup rather than discovered in a browser (ADR-0013 §3).
 *
 * <p>Spring matches an {@code Origin} header against these strings exactly. Every malformed entry
 * below would be accepted by the framework, match nothing, and present as "the console cannot reach
 * the API" — a symptom that sends somebody to the network tab rather than to one line of
 * configuration. That is the whole reason this class exists.
 */
class CorsPropertiesTests {

    private static final String CONSOLE = "http://localhost:5173";
    private static final String DEV_SERVER = "http://localhost:5174";

    @Test
    @DisplayName("the two origins the demo actually serves the console from are accepted")
    void acceptsTheDemoOrigins() {
        CorsProperties properties = new CorsProperties(List.of(CONSOLE, DEV_SERVER));

        assertThat(properties.allowedOrigins()).containsExactly(CONSOLE, DEV_SERVER);
    }

    @Test
    @DisplayName("an https origin and a bare host with no port are both origins")
    void acceptsTheOtherShapesAnOriginTakes() {
        assertThatCode(() -> new CorsProperties(List.of("https://sentinelflow.example", "http://console")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("no browser client is an empty list, not a mistake")
    void acceptsAnEmptyList() {
        assertThat(new CorsProperties(List.of()).allowedOrigins()).isEmpty();
        assertThat(new CorsProperties(null).allowedOrigins())
                .as("an unset property binds to null, and a service with no console is a real deployment")
                .isEmpty();
    }

    @Test
    @DisplayName("surrounding whitespace and empty entries survive a comma-separated variable")
    void toleratesTheShapeAnEnvironmentVariableArrivesIn() {
        // SENTINELFLOW_CORS_ALLOWED_ORIGINS is one string split on commas, and
        // a human writing "a, b" is not making a mistake worth failing startup
        // over. An entry that is only whitespace is dropped rather than refused
        // for the same reason: a trailing comma is a typo, not a security
        // decision.
        CorsProperties properties = new CorsProperties(Arrays.asList("  " + CONSOLE + "  ", "", "   "));

        assertThat(properties.allowedOrigins()).containsExactly(CONSOLE);
    }

    @Test
    @DisplayName("a wildcard is refused, and the message says it is not a permission")
    void refusesAWildcard() {
        assertThatThrownBy(() -> new CorsProperties(List.of("*")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wildcard")
                .hasMessageContaining("ADR-0013");
    }

    @Test
    @DisplayName("a trailing slash is refused, because it is the one that looks right and matches nothing")
    void refusesATrailingSlash() {
        assertThatThrownBy(() -> new CorsProperties(List.of(CONSOLE + "/")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no trailing slash");
    }

    @Test
    @DisplayName("a path, a query and a fragment are all more than an origin")
    void refusesAnythingBeyondSchemeHostAndPort() {
        for (String beyond : List.of(CONSOLE + "/alerts", CONSOLE + "?role=analyst", CONSOLE + "#top")) {
            assertThatThrownBy(() -> new CorsProperties(List.of(beyond)))
                    .as("%s", beyond)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("a host with no scheme is refused, because a browser never sends one")
    void refusesAMissingScheme() {
        assertThatThrownBy(() -> new CorsProperties(List.of("localhost:5173")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheme");
    }

    @Test
    @DisplayName("a scheme that is not http or https is refused")
    void refusesANonHttpScheme() {
        assertThatThrownBy(() -> new CorsProperties(List.of("ftp://localhost:5173")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
