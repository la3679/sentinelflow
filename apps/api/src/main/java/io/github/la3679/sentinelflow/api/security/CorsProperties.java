/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.security;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Which web origins a browser may let read this API (ADR-0013).
 *
 * <h2>This one defaults, and the JWT secret does not</h2>
 *
 * <p>A default signing secret is a credential everybody has. A default origin list grants nothing to
 * anybody who cannot already reach the developer's own loopback interface, and a demo that will not
 * start until an environment variable nobody can guess is set is a demo nobody runs. The default is
 * the compose console and the Vite dev server, and nothing else.
 *
 * <h2>Every entry is checked, because a bad one fails silently</h2>
 *
 * <p>Spring matches an {@code Origin} header against these strings exactly. An entry with a trailing
 * slash, a path, or a missing scheme matches nothing at all, and the symptom is "the console cannot
 * reach the API" — which sends somebody to the network tab rather than to a configuration line. It
 * is checked here instead, at startup, where the message can say which entry and why.
 *
 * @param allowedOrigins absolute {@code http} or {@code https} origins, scheme and authority only.
 *     May be empty, which is the correct configuration for a deployment with no browser client
 *     rather than a mistake.
 */
@ConfigurationProperties("sentinelflow.security.cors")
public record CorsProperties(List<String> allowedOrigins) {

    /** One hour. Long enough that a click is not preceded by a preflight, short enough to change. */
    static final long PREFLIGHT_MAX_AGE_SECONDS = 3600L;

    public CorsProperties {
        allowedOrigins = allowedOrigins == null
                ? List.of()
                : allowedOrigins.stream()
                        .map(String::trim)
                        .filter(entry -> !entry.isEmpty())
                        .peek(CorsProperties::validate)
                        .toList();
    }

    private static void validate(String origin) {
        if ("*".equals(origin)) {
            throw new IllegalArgumentException("sentinelflow.security.cors.allowed-origins contains \"*\". "
                    + "A wildcard is not a permission, it is the absence of one, and ADR-0013 refuses it. "
                    + "Name the origins the console is served from.");
        }
        URI uri;
        try {
            uri = new URI(origin);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("sentinelflow.security.cors.allowed-origins contains \"" + origin
                    + "\", which is not a URI. An Origin header is matched against these exactly, so an entry that "
                    + "cannot be parsed matches nothing and fails as a connection problem.");
        }
        boolean http = "http".equals(uri.getScheme()) || "https".equals(uri.getScheme());
        boolean bare = uri.getPath() == null || uri.getPath().isEmpty();
        if (!http || uri.getHost() == null || !bare || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("sentinelflow.security.cors.allowed-origins contains \"" + origin
                    + "\". An origin is a scheme, a host and optionally a port - \"http://localhost:5173\" - with no "
                    + "trailing slash and no path. A browser sends exactly that in the Origin header, and anything "
                    + "else here matches nothing.");
        }
    }
}
