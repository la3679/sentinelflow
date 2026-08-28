/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.security;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import io.github.la3679.sentinelflow.api.web.CorrelationIdFilter;

/**
 * Who may reach what (ADR-0012 §4, §5).
 *
 * <h2>Stateless, and that is the whole design</h2>
 *
 * No session is created, ever. A request carries a signed token or it carries nothing, and the
 * decision is made from the signature rather than from a lookup — which is what lets a second
 * instance of this service be started without a shared session store appearing beside it.
 *
 * <p>CSRF protection is off <strong>because there is no cookie to forge a request with</strong>.
 * That is the condition under which disabling it is correct, and it stops being correct the moment
 * anything here authenticates from a cookie. The console holds its token in memory, which the
 * frontend rules already require and a test already enforces.
 *
 * <h2>What is open, and what is not</h2>
 *
 * <ul>
 *   <li><strong>Open:</strong> {@code POST /api/v1/auth/login}, because a caller with no token has to
 *       be able to get one; the actuator's health probes, because a liveness check cannot hold one;
 *       and {@code /actuator/prometheus}, because a scrape cannot either.
 *   <li><strong>Open, deliberately and temporarily:</strong> {@code POST /api/v1/transactions}. It is
 *       a machine-to-machine surface whose caller is a payment pipeline rather than a person, so an
 *       operator's password buys nothing there. It needs its own credential together with the rate
 *       limits and payload bounds that belong beside it, which is Phase 8's work, and ADR-0012 §5
 *       records it as a stated gap rather than an oversight.
 *   <li><strong>Authenticated:</strong> everything else, by {@code anyRequest().authenticated()} — a
 *       default-deny, so an endpoint added later is protected by having been forgotten rather than
 *       exposed by it.
 * </ul>
 *
 * <h2>Roles, and the one line that makes them work</h2>
 *
 * Spring Security's {@code hasRole("ANALYST")} looks for an authority named {@code ROLE_ANALYST},
 * and the token's {@code roles} claim carries bare role names. The converter below bridges the two.
 * Without it every role check silently fails closed — every operator gets a 403, which reads like an
 * authorization defect and is a naming one.
 *
 * <h2>Cross-origin, by an allow-list, for the API path only</h2>
 *
 * The console is a separate origin (ADR-0002, ADR-0012 §1) and a browser will not let it read a
 * response from this one without being told it may. ADR-0013 names the origins explicitly rather
 * than by wildcard or pattern, allows no credentials — the token travels in an
 * {@code Authorization} header the console sets itself, and nothing here authenticates from a
 * cookie — and registers the rule under {@code /api/v1/**} only, because {@code /actuator/**} is not
 * a browser surface.
 *
 * <p>None of that is an authorization control. It constrains what a page may read; {@code curl} and
 * every server-side client are unaffected and still meet {@code anyRequest().authenticated()}.
 *
 * <h2>Only in a web application</h2>
 *
 * A filter chain needs a servlet container to sit in, and the schema tests run this context with no
 * web environment at all. Without the condition they would fail on a missing {@code HttpSecurity}
 * bean — an error about wiring, in suites that have nothing to do with HTTP.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
// Turns on @PreAuthorize. Without it the annotation is inert - every method
// carrying one runs for anybody the chain let through, and nothing reports that
// a role check is being ignored, which is the worst way for authorization to
// fail.
@EnableMethodSecurity
public class WebSecurityConfiguration {

    private final ProblemAccessHandlers problems;
    private final CorsProperties cors;

    public WebSecurityConfiguration(ProblemAccessHandlers problems, CorsProperties cors) {
        this.problems = problems;
        this.cors = cors;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http.cors(c -> c.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests.requestMatchers(HttpMethod.POST, "/api/v1/auth/login")
                        .permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**")
                        .permitAll()
                        // Prometheus scrapes this on a schedule and cannot hold
                        // a token that expires every thirty minutes. The series
                        // are aggregate counters and timers with bounded,
                        // low-cardinality labels - no identifier, no amount, no
                        // payload - so what it discloses is the shape of the
                        // traffic rather than anything about a transaction.
                        //
                        // The real answer is to move the actuator to a
                        // management port that is not published to the host,
                        // which is Phase 8's hardening work and not something to
                        // half-do here.
                        .requestMatchers("/actuator/prometheus")
                        .permitAll()
                        // Ingestion, until Phase 8 gives it a credential of its
                        // own. ADR-0012 section 5.
                        .requestMatchers(HttpMethod.POST, "/api/v1/transactions")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(server -> server.jwt(token -> token.jwtAuthenticationConverter(converter()))
                        // The resource server carries its own entry point, so
                        // setting one only on exceptionHandling below would
                        // leave a request with a bad token answering in a
                        // different shape from one with no token at all.
                        .authenticationEntryPoint(problems))
                .exceptionHandling(
                        handling -> handling.authenticationEntryPoint(problems).accessDeniedHandler(problems))
                // No form login and no HTTP Basic. Either would be a second way
                // in, and the one that mattered would be whichever a caller
                // reached for rather than the one this file describes.
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .build();
    }

    /**
     * The allow-list, on the API path and nowhere else (ADR-0013 §1, §4, §5).
     *
     * <p>A preflight asks whether a method and a header would be accepted, so both are named. The
     * {@code Authorization} header is the whole point — without it listed, every authenticated
     * request from the console fails the preflight while an anonymous one succeeds, which reads like
     * a token problem and is a configuration one.
     *
     * <p>{@code allowCredentials} is left false deliberately. Turning it on would permit the ambient
     * cookie flow this design does not use, and would make the disabled CSRF filter above wrong.
     */
    private CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(cors.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(
                List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, CorrelationIdFilter.HEADER));
        // The correlation identifier comes back on every response and ties it to
        // its log line and span. Unexposed, script cannot read it, and the one
        // value a person reporting a failure should quote is invisible to them.
        configuration.setExposedHeaders(List.of(CorrelationIdFilter.HEADER));
        configuration.setMaxAge(CorsProperties.PREFLIGHT_MAX_AGE_SECONDS);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/**", configuration);
        return source;
    }

    private static JwtAuthenticationConverter converter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(SecurityConfiguration.ROLES_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
