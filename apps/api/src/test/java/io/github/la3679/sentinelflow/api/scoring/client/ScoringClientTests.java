/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.scoring.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.github.la3679.sentinelflow.api.domain.TransactionChannel;
import io.github.la3679.sentinelflow.api.domain.TransactionType;
import io.github.la3679.sentinelflow.api.resilience.CircuitBreaker;
import io.github.la3679.sentinelflow.api.scoring.payload.AccountContext;
import io.github.la3679.sentinelflow.api.scoring.payload.Amount;
import io.github.la3679.sentinelflow.api.scoring.payload.ScoreRequest;
import io.github.la3679.sentinelflow.api.scoring.payload.TransactionToScore;

/**
 * The client against a real socket.
 *
 * <p><strong>A stub HTTP server rather than a mocked {@code RestClient}.</strong> Half of what this
 * class does only exists at the transport: a read timeout, a connection refused, a 2xx with no body.
 * A mocked client asserts that the code calls itself, and would pass while the shipped timeouts were
 * on nothing.
 *
 * <p>{@code com.sun.net.httpserver} is in the JDK, so this adds no dependency for a stub that serves
 * one path.
 *
 * <p>Backoff is configured at 1 ms here. The schedule is {@link
 * io.github.la3679.sentinelflow.api.resilience.FullJitterBackOff}'s own and is tested there; what
 * these need is the retry <em>count</em>, and waiting six hundred milliseconds per case to observe it
 * is how a suite becomes one nobody runs.
 */
class ScoringClientTests {

    private static final UUID CORRELATION = UUID.fromString("0198f0a1-2b3c-7d4e-8f90-aaaaaaaaaaaa");
    private static final Instant AT = Instant.parse("2026-08-26T03:10:00Z");

    private static final String SCORED_BODY = """
            {
              "modelVersion": "1.0.0",
              "featureVersion": "1.0.0",
              "modelScore": 99.99986221214922,
              "reasons": [
                {"code": "VELOCITY_5M_HIGH", "contribution": 8.6757},
                {"code": "NEW_DEVICE", "contribution": -1.5}
              ],
              "inferenceDurationMs": 4.269,
              "warnings": []
            }
            """;

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> lastCorrelationHeader = new AtomicReference<>();
    private final List<Integer> responses = new ArrayList<>();

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/score", this::handle);
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    // ----------------------------------------------------------------------- //
    // The happy path
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("a scored response comes back parsed, with the correlation id on the wire")
    void scoresATransaction() {
        respondWith(200);

        ScoringResult result = client().score(request(), CORRELATION);

        assertThat(result.response().modelVersion()).isEqualTo("1.0.0");
        assertThat(result.response().featureVersion()).isEqualTo("1.0.0");
        assertThat(result.response().reasons()).hasSize(2);
        assertThat(result.response().reasons().getFirst().code()).isEqualTo("VELOCITY_5M_HIGH");
        assertThat(result.response().warnings()).isEmpty();
        assertThat(lastCorrelationHeader.get())
                .as("ties this call to the transaction, the event and every log line about it")
                .isEqualTo(CORRELATION.toString());
    }

    @Test
    @DisplayName("the score keeps every digit, because it is a decimal and never a double")
    void theScoreIsNotRounded() {
        respondWith(200);

        BigDecimal score = client().score(request(), CORRELATION).response().modelScore();

        assertThat(score)
                .as("the operating point sits within a fraction of 100, so a rounded score is a "
                        + "different alerting decision (ADR-0007)")
                .isEqualByComparingTo(new BigDecimal("99.99986221214922"));
    }

    @Test
    @DisplayName("the caller's own latency is measured, and it is not the service's own figure")
    void measuresItsOwnLatency() {
        respondWith(200);

        ScoringResult result = client().score(request(), CORRELATION);

        assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0);
        assertThat(result.response().inferenceDurationMs())
                .as("the service measuring itself; the gap between the two is the network")
                .isEqualByComparingTo(new BigDecimal("4.269"));
    }

    // ----------------------------------------------------------------------- //
    // Unavailable: retried, then degraded
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("a 503 is retried to the end of the budget and then reported as unavailable")
    void retriesA503() {
        respondWith(503, 503, 503);

        assertThatThrownBy(() -> client().score(request(), CORRELATION))
                .isInstanceOf(ScoringUnavailableException.class)
                .hasMessageContaining("3 attempts");

        assertThat(requests.get()).as("three attempts, which is two retries").isEqualTo(3);
    }

    @Test
    @DisplayName("a retry that succeeds is a score, not a degraded assessment")
    void recoversOnARetry() {
        respondWith(503, 200);

        ScoringResult result = client().score(request(), CORRELATION);

        assertThat(result.response().modelVersion()).isEqualTo("1.0.0");
        assertThat(requests.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("a read timeout is unavailability, not an error the caller has to interpret")
    void aReadTimeoutIsUnavailable() {
        server.removeContext("/v1/score");
        server.createContext("/v1/score", exchange -> {
            requests.incrementAndGet();
            try {
                // Longer than the read timeout configured below, so the client
                // gives up rather than the test waiting for the server.
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            send(exchange, 200, SCORED_BODY);
        });

        ScoringClientProperties slow = properties(builder -> builder.withReadTimeout(Duration.ofMillis(80)));

        assertThatThrownBy(() -> client(slow).score(request(), CORRELATION))
                .isInstanceOf(ScoringUnavailableException.class);
    }

    @Test
    @DisplayName("a service that is not listening at all is unavailability")
    void aRefusedConnectionIsUnavailable() {
        int port = server.getAddress().getPort();
        server.stop(0);

        ScoringClientProperties gone = properties(port, builder -> builder);

        assertThatThrownBy(() -> client(gone).score(request(), CORRELATION))
                .isInstanceOf(ScoringUnavailableException.class);
    }

    @Test
    @DisplayName("a 2xx with no body is unavailability rather than a score of nothing")
    void anEmptyBodyIsUnavailable() {
        server.removeContext("/v1/score");
        server.createContext("/v1/score", exchange -> {
            requests.incrementAndGet();
            send(exchange, 200, "");
        });

        assertThatThrownBy(() -> client().score(request(), CORRELATION))
                .as("treating an absent body as a score would persist a null model score with no "
                        + "degraded flag, which is a shape the schema does not have")
                .isInstanceOf(ScoringUnavailableException.class);
    }

    // ----------------------------------------------------------------------- //
    // Rejected: never retried, never degraded
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("a 422 is refused once and never retried")
    void doesNotRetryA422() {
        respondWith(422, 200, 200);

        assertThatThrownBy(() -> client().score(request(), CORRELATION))
                .as("it will not become valid, and retrying costs the whole partition (ADR-0006 §4)")
                .isInstanceOf(ScoringRejectedException.class)
                .extracting(error -> ((ScoringRejectedException) error).status())
                .isEqualTo(422);

        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a rejection never opens the breaker, because the service is not sick")
    void aRejectionDoesNotOpenTheBreaker() {
        respondWith(422, 422, 422, 422, 422, 422, 422);
        ScoringClient client = client();

        for (int attempt = 0; attempt < 7; attempt++) {
            assertThatThrownBy(() -> client.score(request(), CORRELATION)).isInstanceOf(ScoringRejectedException.class);
        }

        assertThat(client.circuitState())
                .as("opening here would turn every later transaction into a degraded assessment and "
                        + "hide the contract mismatch behind a system that still appears to work")
                .isEqualTo(CircuitBreaker.State.CLOSED);
    }

    // ----------------------------------------------------------------------- //
    // The breaker
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("consecutive outages open the breaker, and the next call costs nothing")
    void opensTheBreakerAndThenFailsFast() {
        respondWith(503);
        ScoringClient client = client();

        // Five calls, each spending its three attempts.
        for (int call = 0; call < 5; call++) {
            assertThatThrownBy(() -> client.score(request(), CORRELATION))
                    .isInstanceOf(ScoringUnavailableException.class);
        }
        assertThat(client.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);

        int before = requests.get();
        assertThatThrownBy(() -> client.score(request(), CORRELATION))
                .isInstanceOf(ScoringUnavailableException.class)
                .hasMessageContaining("no call was attempted");

        assertThat(requests.get())
                .as("this is the line that stops a scoring outage becoming consumer lag " + "proportional to traffic")
                .isEqualTo(before);
    }

    // ----------------------------------------------------------------------- //
    // Fixtures
    // ----------------------------------------------------------------------- //

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        lastCorrelationHeader.set(exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
        // Drain the request body; leaving it unread makes the client see the
        // connection close rather than the status this test chose.
        exchange.getRequestBody().readAllBytes();

        int index = Math.min(requests.get() - 1, responses.size() - 1);
        int status = responses.isEmpty() ? 200 : responses.get(index);
        send(exchange, status, status == 200 ? SCORED_BODY : problem(status));
    }

    private void respondWith(int... statuses) {
        responses.clear();
        for (int status : statuses) {
            responses.add(status);
        }
    }

    private static String problem(int status) {
        return """
                {"type":"https://example/problem","title":"Refused","status":%d}
                """.formatted(status);
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    private ScoringClient client() {
        return client(properties(builder -> builder));
    }

    private ScoringClient client(ScoringClientProperties properties) {
        return new ScoringClient(
                org.springframework.web.client.RestClient.builder()
                        .baseUrl(properties.baseUrl().toString())
                        .requestFactory(ScoringClientConfiguration.requestFactory(properties))
                        .build(),
                properties,
                new CircuitBreaker(
                        ScoringClient.BREAKER_NAME,
                        properties.circuitBreakerFailureThreshold(),
                        properties.circuitBreakerOpenDuration(),
                        Clock.systemUTC()));
    }

    /** A tweak applied to the defaults, so each test states only what it changes. */
    @FunctionalInterface
    private interface Tweak {
        Settings apply(Settings settings);
    }

    private record Settings(Duration connectTimeout, Duration readTimeout) {
        Settings withReadTimeout(Duration value) {
            return new Settings(connectTimeout, value);
        }
    }

    private ScoringClientProperties properties(Tweak tweak) {
        return properties(server.getAddress().getPort(), tweak);
    }

    private ScoringClientProperties properties(int port, Tweak tweak) {
        Settings settings = tweak.apply(new Settings(Duration.ofSeconds(1), Duration.ofSeconds(2)));
        return new ScoringClientProperties(
                URI.create("http://127.0.0.1:" + port),
                settings.connectTimeout(),
                settings.readTimeout(),
                2,
                // 1 ms rather than the shipped 100 ms: these assert the retry
                // count, and the schedule has its own tests.
                Duration.ofMillis(1),
                Duration.ofMillis(2),
                5,
                Duration.ofSeconds(30));
    }

    private static ScoreRequest request() {
        return new ScoreRequest(
                new TransactionToScore(
                        UUID.fromString("0198f0a1-2b3c-7d4e-8f90-1a2b3c4d5e6f"),
                        "ACC-000123",
                        "MER-0042",
                        "5411",
                        TransactionType.PURCHASE,
                        TransactionChannel.CARD_NOT_PRESENT,
                        new Amount("1200.00", "GBP"),
                        "FR",
                        "DEV-fedcba987654",
                        AT),
                new AccountContext(
                        1, 86_400, AT.minus(400, ChronoUnit.DAYS), new Amount("2500.0000", "GBP"), List.of(), false));
    }
}
