/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.security;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.la3679.sentinelflow.api.domain.RoleCode;
import io.github.la3679.sentinelflow.api.domain.UserStatus;
import io.github.la3679.sentinelflow.api.persistence.entity.User;
import io.github.la3679.sentinelflow.api.persistence.entity.UserCredential;
import io.github.la3679.sentinelflow.api.persistence.repository.UserCredentialRepository;
import io.github.la3679.sentinelflow.api.persistence.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Checks a password and, if it is right, says who the caller is (ADR-0012 §1).
 *
 * <h2>Four ways to fail, one answer</h2>
 *
 * No such user, no credential, a wrong password, and a disabled account all produce the same
 * refusal. The distinctions are real and every one of them is something a caller is not entitled to
 * learn: "no credential" tells an attacker they have found the system principal, and "that user is
 * disabled" confirms the username. The log records which it was, because an operator debugging
 * their own login is entitled to more than a caller is.
 *
 * <h2>The comparison is made even when there is nothing to compare against</h2>
 *
 * A username that does not exist still costs a hash comparison against a fixed dummy value. Without
 * it, a missing user returns in microseconds and a real one takes the tens of milliseconds BCrypt is
 * designed to cost, and the difference is a usable oracle for enumerating usernames — through an
 * endpoint that is, necessarily, open to anybody.
 */
@Service
public class OperatorLoginService {

    private static final Logger log = LoggerFactory.getLogger(OperatorLoginService.class);

    /**
     * A well-formed hash of a value nothing knows, compared against when no credential was found.
     *
     * <p>BCrypt of a random string, generated once at class-load. A constant baked into the source
     * would be a published hash; this one exists only for the time this process runs, and nothing
     * can authenticate against it because nothing knows what it hashes.
     */
    private static final String NO_SUCH_CREDENTIAL_PLACEHOLDER = "placeholder-" + UUID.randomUUID();

    private final UserRepository users;
    private final UserCredentialRepository credentials;
    private final PasswordEncoder passwordEncoder;
    private final TokenIssuer tokens;
    private final MeterRegistry meters;
    private final String placeholderHash;

    public OperatorLoginService(
            UserRepository users,
            UserCredentialRepository credentials,
            PasswordEncoder passwordEncoder,
            TokenIssuer tokens,
            MeterRegistry meters) {
        this.users = users;
        this.credentials = credentials;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.meters = meters;
        this.placeholderHash = passwordEncoder.encode(NO_SUCH_CREDENTIAL_PLACEHOLDER);
    }

    /**
     * Authenticate an operator and issue their token.
     *
     * @throws InvalidCredentialsException if the username is unknown, the account cannot log in, or
     *     the password is wrong. Deliberately one exception for all three.
     */
    @Transactional(readOnly = true)
    public LoginResult login(String username, String password) {
        Optional<User> found = users.findByUsername(username);

        // The comparison happens on every path, including the ones that have
        // already failed, so the endpoint takes the same time either way.
        String hash = found.flatMap(user -> credentials.findById(user.getId()))
                .map(UserCredential::getPasswordHash)
                .orElse(placeholderHash);
        boolean passwordMatches = passwordEncoder.matches(password, hash);

        User user = found.orElse(null);
        if (user == null) {
            return refuse(username, "no such user");
        }
        if (user.getStatus() != UserStatus.ACTIVE) {
            return refuse(username, "the account is " + user.getStatus());
        }
        if (!passwordMatches) {
            return refuse(username, "the password did not match");
        }

        List<RoleCode> roles = users.findRoleCodes(user.getId());
        if (roles.contains(RoleCode.SYSTEM)) {
            // Unreachable while the system principal has no credential row, and
            // asserted anyway: SYSTEM is what the pipeline's own actions are
            // attributed to, and a person holding it would make an automated
            // action and a human one indistinguishable in the audit trail.
            return refuse(username, "SYSTEM is not a login role");
        }

        meters.counter("sentinelflow.auth.logins", "outcome", "success").increment();
        log.info("Operator {} authenticated with roles {}", user.getUsername(), roles);
        return new LoginResult(tokens.issue(user.getId(), roles, Instant.now()), user.getId(), user.getDisplayName());
    }

    /**
     * The token, and who it belongs to.
     *
     * <p><strong>The identity is here rather than inside {@link TokenIssuer.IssuedToken}</strong>,
     * because a token issuer's job is to sign claims and a display name is not one. The token
     * already carries the identifier as its subject; this carries it again in a shape a client can
     * read without decoding a structure ADR-0012 says not to parse.
     *
     * <p>Without this, a console can sign in and still not know who it signed in as - which is
     * exactly why "assign this alert to me" was not buildable before ADR-0019, and why the
     * investigation screen said so rather than drawing a button that could not work.
     */
    public record LoginResult(TokenIssuer.IssuedToken token, UUID operatorId, String displayName) {}

    private LoginResult refuse(String username, String reason) {
        meters.counter("sentinelflow.auth.logins", "outcome", "failure").increment();
        // The username is the caller's own input and safe to log; the reason is
        // for whoever reads the log and is never in the response.
        log.info("Login refused for {}: {}", username, reason);
        throw new InvalidCredentialsException();
    }

    /**
     * The single refusal.
     *
     * <p>No detail, and no field to attach one to. A message naming which half was wrong would turn
     * the login endpoint into a username oracle, and there is no legitimate caller who needs to know
     * more than that the pair was not accepted.
     */
    public static class InvalidCredentialsException extends RuntimeException {
        public InvalidCredentialsException() {
            super("The username and password were not accepted");
        }
    }
}
