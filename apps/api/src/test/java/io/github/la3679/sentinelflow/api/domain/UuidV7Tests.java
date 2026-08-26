/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The ordering guarantee, tested against a clock that does not move.
 *
 * <p>A UUIDv7 generator is trivially ordered when time passes between calls, which is the one case
 * that proves nothing: index locality matters under a burst, and a burst is precisely when many
 * identifiers share a millisecond. Every test here that matters pins the clock so the monotonic
 * counter is the only thing that can produce an order.
 */
class UuidV7Tests {

    /** A clock frozen at one instant, so every identifier issued lands in the same millisecond. */
    private static Clock frozenAt(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    @Test
    @DisplayName("the layout is version 7 with the RFC 9562 variant")
    void layoutIsVersion7() {
        UUID id = UuidV7.randomUuid();

        assertThat(id.version()).isEqualTo(7);
        // Variant 2 is what java.util.UUID reports for the 0b10 RFC variant.
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    @DisplayName("the embedded timestamp is the generation time")
    void timestampIsEmbedded() {
        Instant when = Instant.parse("2026-08-26T12:00:00Z");
        UuidV7 generator = new UuidV7(frozenAt(when.toString()), new SecureRandom());

        assertThat(UuidV7.timestampMillis(generator.generate())).isEqualTo(when.toEpochMilli());
    }

    @Test
    @DisplayName("reading a timestamp out of a version 4 UUID is refused")
    void timestampRejectsOtherVersions() {
        // UUID.randomUUID is version 4: those 48 bits are random, and returning
        // a number for them would be returning an invented time.
        assertThatThrownBy(() -> UuidV7.timestampMillis(UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not a version 7 UUID");
    }

    @Test
    @DisplayName("identifiers issued in one millisecond are still strictly increasing")
    void monotonicWithinAMillisecond() {
        UuidV7 generator = new UuidV7(frozenAt("2026-08-26T12:00:00Z"), new SecureRandom());

        List<UUID> issued = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            issued.add(generator.generate());
        }

        // Lexicographic order on the canonical text is the order PostgreSQL's
        // uuid type sorts by, which is the order the index cares about.
        for (int i = 1; i < issued.size(); i++) {
            assertThat(issued.get(i).toString())
                    .as("identifier %d must sort after identifier %d", i, i - 1)
                    .isGreaterThan(issued.get(i - 1).toString());
        }
    }

    @Test
    @DisplayName("a stalled clock fails loudly rather than wrapping the counter")
    void exhaustedCounterOnAStalledClockThrows() {
        // 4096 identifiers exhaust the 12-bit counter. Wrapping would emit an
        // identifier smaller than one already issued and silently break the
        // ordering guarantee this class exists to provide, so it waits for the
        // clock - and a clock that never advances is a broken environment.
        UuidV7 generator = new UuidV7(frozenAt("2026-08-26T12:00:00Z"), new SecureRandom());
        for (int i = 0; i < 4096; i++) {
            generator.generate();
        }

        assertThatThrownBy(generator::generate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Clock did not advance");
    }

    @Test
    @DisplayName("a clock that goes backwards does not make the sequence go backwards")
    void backwardsClockDoesNotRewindTheSequence() {
        // An NTP correction or a resumed laptop moves the clock back. The
        // sequence must not follow it.
        MutableClock clock = new MutableClock(Instant.parse("2026-08-26T12:00:00Z"));
        UuidV7 generator = new UuidV7(clock, new SecureRandom());

        UUID before = generator.generate();
        clock.set(Instant.parse("2026-08-26T11:59:59Z"));
        UUID after = generator.generate();

        assertThat(after.toString()).isGreaterThan(before.toString());
        assertThat(UuidV7.timestampMillis(after)).isEqualTo(UuidV7.timestampMillis(before));
    }

    @Test
    @DisplayName("concurrent callers each get a distinct identifier")
    void concurrentGenerationIsUnique() throws InterruptedException {
        int threads = 8;
        int perThread = 400;
        UuidV7 generator = new UuidV7(frozenAt("2026-08-26T12:00:00Z"), new SecureRandom());
        Set<UUID> issued = java.util.Collections.synchronizedSet(new HashSet<>());
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        issued.add(generator.generate());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        // 3200 identifiers in one frozen millisecond, under the 4096 ceiling.
        assertThat(issued).hasSize(threads * perThread);
    }

    /** A clock whose instant can be moved, including backwards. */
    private static final class MutableClock extends Clock {

        private volatile Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void set(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
