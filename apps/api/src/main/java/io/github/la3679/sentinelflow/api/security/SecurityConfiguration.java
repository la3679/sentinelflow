/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.security;

import java.nio.charset.StandardCharsets;

import javax.crypto.spec.SecretKeySpec;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;

/**
 * The cryptography behind ADR-0012: what hashes a password, and what signs and verifies a token.
 *
 * <h2>Separate from {@link WebSecurityConfiguration}, deliberately</h2>
 *
 * These three beans are needed by code that has nothing to do with HTTP — the seed encodes an
 * operator's password, and the login service verifies one — while a filter chain is meaningless
 * without a servlet container. Declaring them together would make every schema test fail on a
 * missing {@code HttpSecurity} bean, because those run the context with no web environment at all.
 *
 * <h2>One symmetric key, used to sign and to verify</h2>
 *
 * HMAC rather than a key pair, because this service is both the issuer and the only audience. An
 * asymmetric key earns its complexity when something else has to verify a token without being able
 * to mint one, and nothing here does.
 */
@Configuration
public class SecurityConfiguration {

    /** The claim the token carries roles in. Written by {@link TokenIssuer}, read by the filter chain. */
    static final String ROLES_CLAIM = "roles";

    private final JwtProperties jwt;

    public SecurityConfiguration(JwtProperties jwt) {
        this.jwt = jwt;
    }

    /**
     * The delegating encoder, so a stored hash names the algorithm that produced it.
     *
     * <p>{@code {bcrypt}$2a$10$...} rather than a bare BCrypt string. The prefix is what lets a later
     * move to another algorithm read old hashes and re-encode them on next login, without a data
     * migration and without a column that means two different things at once.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(new OctetSequenceKey.Builder(secretKey())
                .algorithm(JWSAlgorithm.HS256)
                .build())));
    }

    @Bean
    JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withSecretKey(secretKey())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    private SecretKeySpec secretKey() {
        return new SecretKeySpec(jwt.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
