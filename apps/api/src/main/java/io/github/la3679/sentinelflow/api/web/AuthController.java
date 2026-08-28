/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web;

import jakarta.validation.Valid;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.la3679.sentinelflow.api.security.OperatorLoginService;
import io.github.la3679.sentinelflow.api.security.TokenIssuer;
import io.github.la3679.sentinelflow.api.web.dto.LoginRequest;
import io.github.la3679.sentinelflow.api.web.dto.TokenResponse;

/**
 * The one endpoint a caller may reach without a token (ADR-0012 §1).
 *
 * <p>It validates, delegates, and maps — the same rule every other controller here follows. Whether
 * the password is right, what a refusal may say, and how long the attempt takes are all
 * {@link OperatorLoginService}'s, because every one of them is a security property rather than a
 * transport concern.
 *
 * <p><strong>No logging in this class.</strong> The request object holds a password, and the
 * temptation to log "login attempt from X" beside it is exactly how one ends up in a file. The
 * service logs the outcome, which is the part worth having.
 */
@RestController
@RequestMapping(path = "/api/v1/auth", produces = MediaType.APPLICATION_JSON_VALUE)
public class AuthController {

    private final OperatorLoginService logins;

    public AuthController(OperatorLoginService logins) {
        this.logins = logins;
    }

    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    TokenResponse login(@Valid @RequestBody LoginRequest request) {
        TokenIssuer.IssuedToken issued = logins.login(request.username(), request.password());
        return TokenResponse.bearer(issued.value(), issued.expiresAt(), issued.roles());
    }
}
