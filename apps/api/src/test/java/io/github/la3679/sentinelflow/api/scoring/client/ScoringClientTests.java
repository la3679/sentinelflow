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
import io.github.la3679.sentinelflow.api.scoring.payload.ModelInfoResponse;
import io.github.la3679.sentinelflow.api.scoring.payload.ScoreRequest;
import io.github.la3679.sentinelflow.api.scoring.payload.TransactionToScore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

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

    private static final String MODEL_BODY = """
            {
              "modelVersion": "1.0.0",
              "featureVersion": "1.0.0",
              "algorithm": "gradient-boosting",
              "trainedAt": "2026-07-19T08:30:00Z",
              "artifactSha256": "abc123",
              "datasetFingerprint": "fp-1",
              "metrics": {
                "precision": 0.82, "recall": 0.61, "f1": 0.7, "averagePrecision": 0.74,
                "rocAuc": 0.95, "falsePositiveRate": 0.01, "operatingThreshold": 62.5,
                "alertVolumeAtThreshold": 120
              }
            }
            """;

    private HttpServer server;

    /**
     * The registry every client in this class is built against.
     *
     * <p>A real {@link SimpleMeterRegistry} rather than a mock: what the metric assertions need to
     * know is the value a scrape would report, and a verified interaction with a mocked registry
     * would pass just as happily against a counter registered under the wrong name.
     */
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> lastCorrelationHeader = new AtomicReference<>();
    private final AtomicReference<String> lastUpgradeHeader = new AtomicReference<>();
    private final List<Integer> responses = new ArrayList<>();
    private final AtomicInteger modelRequests = new AtomicInteger();
    private final List<Integer> modelResponses = new ArrayList<>();
    private final AtomicReference<String> modelBody = new AtomicReference<>(MODEL_BODY);

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/score", this::handle);
        server.createContext("/v1/model", this::handleModel);
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
    @DisplayName("no request asks to upgrade to a protocol the scoring service does not speak")
    void doesNotAttemptAnHttp2Upgrade() {
        respondWith(200);

        client().score(request(), CORRELATION);

        // The JDK's HttpClient defaults to HTTP_2, which against an http:// URI
        // means every request carries `Upgrade: h2c`. The scoring service is
        // uvicorn and serves HTTP/1.1 only: it logs "Unsupported upgrade
        // request", fails to read the body that arrived with it, and answers 422
        // naming the whole body as invalid.
        //
        // That is not hypothetical. It is what happened the first time the
        // pipeline ran against the real stack rather than against this stub -
        // every scoring call rejected, 13,455 assessments degraded and 6,224
        // events dead-lettered - and this suite was green throughout, because
        // com.sun.net.httpserver answers an upgrade attempt by ignoring it.
        //
        // So the assertion is on the wire rather than on the setting: a client
        // configured back to HTTP_2 would fail here even though this stub would
        // still serve it.
        assertThat(lastUpgradeHeader.get())
                .as("uvicorn cannot read a request that asks to become h2c")
                .isNull();
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
    // What a scrape sees
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("each of the four outcomes is counted under its own name")
    void countsEveryOutcomeSeparately() {
        ScoringClient client = client();

        respondWith(200);
        client.score(request(), CORRELATION);

        respondWith(422);
        assertThatThrownBy(() -> client.score(request(), CORRELATION)).isInstanceOf(ScoringRejectedException.class);

        // Five failing calls open the breaker; the sixth is refused without an
        // attempt, which is the fourth outcome.
        for (int call = 0; call < 5; call++) {
            respondWith(503, 503, 503);
            assertThatThrownBy(() -> client.score(request(), CORRELATION))
                    .isInstanceOf(ScoringUnavailableException.class);
        }
        assertThatThrownBy(() -> client.score(request(), CORRELATION))
                .isInstanceOf(ScoringUnavailableException.class)
                .hasMessageContaining("no call was attempted");

        assertThat(calls("scored")).isEqualTo(1);
        assertThat(calls("rejected")).isEqualTo(1);
        assertThat(calls("unavailable"))
                .as("one per call that spent its whole budget, not one per HTTP attempt")
                .isEqualTo(5);
        assertThat(calls("breaker_open")).isEqualTo(1);
    }

    @Test
    @DisplayName("a call the breaker refused is counted but never timed")
    void doesNotPutRefusedCallsInTheLatencyHistogram() {
        respondWith(503);
        ScoringClient client = client();

        for (int call = 0; call < 5; call++) {
            assertThatThrownBy(() -> client.score(request(), CORRELATION))
                    .isInstanceOf(ScoringUnavailableException.class);
        }
        assertThat(client.circuitState()).isEqualTo(CircuitBreaker.State.OPEN);

        long timedBefore = timed("unavailable");
        assertThatThrownBy(() -> client.score(request(), CORRELATION))
                .isInstanceOf(ScoringUnavailableException.class)
                .hasMessageContaining("no call was attempted");

        assertThat(calls("breaker_open")).isEqualTo(1);
        assertThat(timed("unavailable"))
                .as("a refused call takes no measurable time, and folding those zeroes into the "
                        + "histogram would make an outage look like the fastest scoring ever seen")
                .isEqualTo(timedBefore);
    }

    @Test
    @DisplayName("the latency histogram carries the caller's whole call, retries included")
    void timesTheWholeCallRatherThanOneAttempt() {
        respondWith(503, 503, 200);
        ScoringClient client = client();

        client.score(request(), CORRELATION);

        Timer timer = meters.find(ScoringClient.DURATION_METRIC)
                .tag("outcome", "scored")
                .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS))
                .as("three attempts and two backoffs, so the recorded time is the call rather "
                        + "than the last request in it")
                .isPositive();
    }

    @Test
    @DisplayName("no metric carries anything taken from the request")
    void keepsTheLabelSpaceClosed() {
        respondWith(200);
        client().score(request(), CORRELATION);

        assertThat(meters.getMeters())
                .filteredOn(meter -> meter.getId().getName().startsWith("sentinelflow.scoring"))
                .isNotEmpty()
                .allSatisfy(meter -> assertThat(meter.getId().getTags())
                        .as("ADR-0016 section 2: a label value comes from a closed set fixed in "
                                + "code, never from a request, a payload or a row")
                        // `le` is Micrometer's own bucket-boundary tag, and its
                        // values are the boundaries declared in ScoringClient -
                        // which is as closed a set as `outcome` is.
                        .allSatisfy(tag -> assertThat(tag.getKey()).isIn("outcome", "le")));
    }

    // ----------------------------------------------------------------------- //
    // Model metadata, which is a read on behalf of a screen
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("the loaded model's metadata comes back parsed")
    void readsTheLoadedModel() {
        modelResponses.add(200);

        ModelInfoResponse info = client().modelInfo(CORRELATION);

        assertThat(info.modelVersion()).isEqualTo("1.0.0");
        assertThat(info.algorithm()).isEqualTo("gradient-boosting");
        assertThat(info.metrics().precision()).isEqualByComparingTo("0.82");
        assertThat(lastCorrelationHeader.get()).isEqualTo(CORRELATION.toString());
    }

    @Test
    @DisplayName("a figure the scoring service adds does not stop the API answering")
    void toleratesAnUnknownField() {
        modelBody.set("""
                {"modelVersion":"1.0.0","featureVersion":"1.0.0","algorithm":"gradient-boosting",
                 "trainedAt":"2026-07-19T08:30:00Z","artifactSha256":"abc",
                 "metrics":{"precision":0.82,"recall":0.61,"f1":0.7,"averagePrecision":0.74,
                            "falsePositiveRate":0.01,"operatingThreshold":62.5},
                 "somethingNew":42}
                """);
        modelResponses.add(200);

        // Unlike the score response, where an unknown field is a contract change
        // worth noticing: this is metadata for one read-only screen, and a
        // scoring service that publishes an extra figure must not stop the API
        // answering a screen that would simply not show it.
        assertThat(client().modelInfo(CORRELATION).modelVersion()).isEqualTo("1.0.0");
    }

    @Test
    @DisplayName("no model loaded is unavailable, not a distinct failure a caller has to handle")
    void treatsNoModelAsUnavailable() {
        modelResponses.add(503);

        assertThatThrownBy(() -> client().modelInfo(CORRELATION)).isInstanceOf(ScoringUnavailableException.class);
    }

    @Test
    @DisplayName("reading the metadata neither opens the breaker nor closes it")
    void leavesTheBreakerAlone() {
        ScoringClient client = client();
        for (int i = 0; i < 8; i++) {
            modelResponses.add(500);
        }

        for (int attempt = 0; attempt < 8; attempt++) {
            assertThatThrownBy(() -> client.modelInfo(CORRELATION)).isInstanceOf(ScoringUnavailableException.class);
        }

        // Eight failures against a threshold of five. The breaker exists so a
        // scoring outage costs the consumer nothing per record; a screen
        // somebody refreshes must not be able to open it, because that would let
        // a dashboard degrade every assessment.
        assertThat(client.circuitState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("the metadata read is not retried")
    void doesNotRetryTheMetadataRead() {
        modelResponses.add(500);

        assertThatThrownBy(() -> client().modelInfo(CORRELATION)).isInstanceOf(ScoringUnavailableException.class);

        // One read, one timeout, no retry. A screen being refreshed is not worth
        // three requests to a service that has just said no.
        assertThat(modelRequests.get()).isEqualTo(1);
    }

    // ----------------------------------------------------------------------- //
    // Fixtures
    // ----------------------------------------------------------------------- //

    private void handleModel(HttpExchange exchange) throws IOException {
        modelRequests.incrementAndGet();
        lastCorrelationHeader.set(exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
        exchange.getRequestBody().readAllBytes();

        int index = Math.min(modelRequests.get() - 1, modelResponses.size() - 1);
        int status = modelResponses.isEmpty() ? 200 : modelResponses.get(index);
        send(exchange, status, status == 200 ? modelBody.get() : problem(status));
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        lastCorrelationHeader.set(exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
        lastUpgradeHeader.set(exchange.getRequestHeaders().getFirst("Upgrade"));
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
                        Clock.systemUTC()),
                meters);
    }

    /** How many calls the counter recorded for one outcome. */
    private double calls(String outcome) {
        Counter counter =
                meters.find(ScoringClient.CALLS_METRIC).tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    /** How many observations the latency histogram holds for one outcome. */
    private long timed(String outcome) {
        Timer timer = meters.find(ScoringClient.DURATION_METRIC)
                .tag("outcome", outcome)
                .timer();
        return timer == null ? 0 : timer.count();
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
