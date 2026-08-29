/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.github.la3679.sentinelflow.api.persistence.repository.UserRepository;
import io.github.la3679.sentinelflow.api.seed.SeedRunner;

/**
 * The refusal that turns a per-message failure into a startup failure.
 *
 * <p>Written against a defect this project actually had: with the {@code system} principal absent,
 * every {@code transaction.created} event was retried five times and dead-lettered, while ingestion
 * kept returning 202 and the health endpoint kept reporting every component operational.
 */
class ReferenceDataVerifierTests {

    private final UserRepository users = mock(UserRepository.class);
    private final ReferenceDataVerifier verifier = new ReferenceDataVerifier(users);

    @Test
    @DisplayName("refuses to start when the system principal is absent, and says how to restore it")
    void refusesWithoutTheSystemPrincipal() {
        when(users.findSystemPrincipalId()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> verifier.run(null))
                .as("a message that names the row, the migration and the repair is the difference "
                        + "between a five-minute fix and an afternoon reading consumer logs")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("system")
                .hasMessageContaining("V1__identity_and_reference_data.sql");
    }

    @Test
    @DisplayName("starts against a database the migrations produced")
    void startsWithIt() {
        when(users.findSystemPrincipalId()).thenReturn(Optional.of(UUID.randomUUID()));

        assertThatCode(() -> verifier.run(null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("runs before the seed, which has nothing to seed into a database that cannot work")
    void runsBeforeTheSeed() {
        // A constant rather than a comment, because two runners left on the
        // default precedence run in whatever order the context produced - which
        // fails intermittently rather than consistently.
        assertThat(ReferenceDataVerifier.ORDER).isLessThan(SeedRunner.ORDER);
    }
}
