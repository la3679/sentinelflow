/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.github.la3679.sentinelflow.api.persistence.repository.UserRepository;

/**
 * Refuses to start against a database that is missing the reference data the migrations insert.
 *
 * <p><strong>Why this exists.</strong> On 2026-08-29 every {@code transaction.created} event on the
 * local demo stack was dead-lettered after five attempts with an {@code IllegalStateException}. The
 * cause was one absent row: the {@code system} principal, which V1 inserts as reference data and
 * which an earlier session's {@code TRUNCATE users ... CASCADE} had removed. Nothing noticed,
 * because that row is read at exactly one moment — when an assessment raises an alert — so ingestion
 * accepted transactions, health reported every component operational, and the whole pipeline
 * silently failed at its last step.
 *
 * <p>The failure the row's absence produces is expensive in the worst way: a per-message error, five
 * retries deep, inside a Kafka consumer, on records that would fail identically for ever. Checking
 * once at startup turns it into a refusal to start with a message naming the fix — which is what
 * this project does everywhere else that configuration can be wrong.
 *
 * <p><strong>Only the reference data an automated action cannot proceed without.</strong> Not the
 * roles, which are checked by the foreign keys that point at them, and not the demo operators, which
 * are the seed's and are legitimately absent in a database nobody seeded. The system principal is
 * different: {@code alert_actions.actor_id} is {@code NOT NULL}, so its absence makes an ordinary
 * outcome — an alert being raised — impossible to record.
 */
@Component
@Order(ReferenceDataVerifier.ORDER)
public class ReferenceDataVerifier implements ApplicationRunner {

    /**
     * Before {@link io.github.la3679.sentinelflow.api.seed.SeedRunner}, which is {@code 0}.
     *
     * <p>Seeding a database whose reference data is missing writes demo rows into something that
     * cannot process them, and the seed's own party loader would report success while doing it.
     */
    public static final int ORDER = -100;

    private final UserRepository users;

    public ReferenceDataVerifier(UserRepository users) {
        this.users = users;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Propagates and stops startup. Refusing to start is the point: this
        // service cannot record an automated action without an actor, and a
        // process that came up healthy and then dead-lettered every event is
        // strictly harder to diagnose than one that never came up.
        users.findSystemPrincipalId()
                .orElseThrow(() -> new IllegalStateException(
                        "The 'system' principal is missing from the users table. V1 inserts it as reference data, "
                                + "so this database is not one these migrations produced unaltered — most likely its "
                                + "users table was truncated. Without it no alert can be raised, because "
                                + "alert_actions.actor_id is NOT NULL and there is nothing to attribute the action to. "
                                + "Restore it by recreating the database, or by re-running the two INSERT statements at "
                                + "the end of V1__identity_and_reference_data.sql."));
    }
}
