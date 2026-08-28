/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.la3679.sentinelflow.api.persistence.repository.AlertRepository;
import io.github.la3679.sentinelflow.api.persistence.repository.TransactionRepository;
import io.github.la3679.sentinelflow.api.support.AbstractPostgresTest;
import io.github.la3679.sentinelflow.api.support.SchemaFixtures;

/**
 * One allocator per reference namespace, tests included.
 *
 * <p>{@code transaction_reference} and {@code alert_reference} each have a unique index over them
 * and each have exactly one thing entitled to hand out a value: {@code transaction_reference_seq}
 * and {@code alert_reference_seq}. That is a property of the whole running system rather than of the
 * production code alone — a test fixture that mints its own references is a second allocator, and
 * the database cannot tell the difference.
 *
 * <h2>This is a regression test, and the defect it pins only ever appeared on a runner</h2>
 *
 * {@code SchemaFixtures} used to build {@code TXN-} and {@code ALT-} from an in-JVM counter starting
 * at 1 while the application read the sequences, also starting at 1. One container serves the whole
 * fork, so the two met as soon as the application had ingested as many transactions as the fixtures
 * had written — which depends on the order the suites happen to run in.
 * {@code TransactionIngestionIT.retryReturnsTheOriginalResult} answered 500 on a duplicate
 * {@code TXN-000005} in CI and passed on the machine the change was written on.
 *
 * <p>A larger starting offset for the fixture counter would have made that unlikely rather than
 * impossible, and "unlikely" is what the original counter already was.
 *
 * <h2>Consecutive, not merely distinct</h2>
 *
 * Asserting the two allocators never repeat each other would be a weak test: two independent
 * counters standing far apart also produce distinct values, and that is exactly the state the old
 * code was usually in. So these assert the stronger property — draw alternately from the fixture and
 * from the application and <strong>every value is one more than the value before it</strong>, which
 * only one shared sequence can produce. A second counter fails it on the first pair.
 *
 * <p>Nothing else allocates while this runs: {@code AbstractPostgresTest} disables the consumers,
 * there is no broker in this context, and Failsafe runs one class at a time. Consecutiveness is
 * therefore a property this test may assert rather than a race it would be exposed to.
 */
class ReferenceAllocationIT extends AbstractPostgresTest {

    /** Alternating draws. Enough to rule out a coincidence, small enough to stay cheap. */
    private static final int DRAWS = 5;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private AlertRepository alerts;

    @Test
    @DisplayName("the fixtures and the application draw transaction references from one sequence")
    void oneAllocatorOwnsTheTransactionNamespace() {
        List<String> issued = interleaved(
                () -> SchemaFixtures.nextTransactionReference(jdbc), transactions::nextTransactionReference);

        assertThat(issued).allMatch(reference -> reference.matches("TXN-[0-9]{6}"));
        assertConsecutive(issued);
    }

    @Test
    @DisplayName("the fixtures and the application draw alert references from one sequence")
    void oneAllocatorOwnsTheAlertNamespace() {
        List<String> issued = interleaved(() -> SchemaFixtures.nextAlertReference(jdbc), alerts::nextAlertReference);

        assertThat(issued).allMatch(reference -> reference.matches("ALT-[0-9]{4}"));
        assertConsecutive(issued);
    }

    /** References drawn alternately from the fixtures and from the application. */
    private static List<String> interleaved(Supplier<String> fixture, Supplier<String> application) {
        List<String> issued = new ArrayList<>();
        for (int draw = 0; draw < DRAWS; draw++) {
            issued.add(fixture.get());
            issued.add(application.get());
        }
        return issued;
    }

    private static void assertConsecutive(List<String> issued) {
        for (int index = 1; index < issued.size(); index++) {
            assertThat(numberIn(issued.get(index)))
                    .as(
                            "%s followed %s; a gap or a repeat means the fixtures and the application are "
                                    + "counting separately, and they collide as soon as the two meet",
                            issued.get(index), issued.get(index - 1))
                    .isEqualTo(numberIn(issued.get(index - 1)) + 1);
        }
    }

    /** The numeric part, which is what the sequence actually allocates. */
    private static long numberIn(String reference) {
        return Long.parseLong(reference.substring(reference.indexOf('-') + 1));
    }
}
