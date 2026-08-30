/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.scoring.client;

import java.net.http.HttpClient;
import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import io.github.la3679.sentinelflow.api.resilience.CircuitBreaker;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * The one outbound HTTP client this application makes, and the breaker in front of it.
 *
 * <p><strong>A dedicated {@link RestClient}, not the shared builder.</strong> The timeouts here are a
 * decision about one dependency taken in ADR-0008 §3; putting them on an application-wide builder
 * would apply a two-second read budget to whatever this service calls next, chosen for reasons that
 * have nothing to do with it.
 *
 * <p><strong>The timeouts live on the request factory, not at the call site.</strong> A timeout a
 * caller has to remember to pass is one the next caller forgets, and forgetting means a consumer
 * thread blocked on a socket that will never answer — a partition stalled indefinitely rather than a
 * scoring outage survived.
 *
 * <p>{@code JdkClientHttpRequestFactory} over the JDK's own {@code HttpClient}: no extra dependency.
 * The connect timeout belongs to the client and the read timeout to the factory, which is why both
 * are set here rather than looking like an oversight in one of them.
 *
 * <p><strong>Message converters are left at their defaults, deliberately.</strong> The response
 * record declares {@code BigDecimal} where the contract carries a number, and Jackson binds a JSON
 * number straight to it without a {@code double} in between — so ADR-0007 is satisfied by the type,
 * not by a converter someone has to keep configured. Handing the application's own mapper to this
 * client would couple the scoring wire to coercion rules chosen for inbound API requests.
 */
@Configuration
public class ScoringClientConfiguration {

    /** The breaker gauge's name, here so the test asserts against the shipped string. */
    static final String BREAKER_STATE_METRIC = "sentinelflow.scoring.breaker.state";

    /**
     * The request factory carrying ADR-0008 §3's connect and read timeouts.
     *
     * <p>Package-private and separate so a test can build the same factory against a stub server
     * without standing up an application context — which matters, because a timeout asserted through
     * a differently-constructed client is not the timeout that ships.
     */
    static JdkClientHttpRequestFactory requestFactory(ScoringClientProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                // HTTP/1.1, explicitly, and this one is not a preference.
                //
                // The JDK's HttpClient defaults to HTTP_2, and against an
                // `http://` URI that means every request carries `Upgrade: h2c`
                // and `Connection: Upgrade, HTTP2-Settings` in the hope of
                // negotiating cleartext HTTP/2. The scoring service is uvicorn,
                // which serves HTTP/1.1 only: it logs "Unsupported upgrade
                // request", fails to read the body that came with it, and answers
                // 422 naming the whole body as invalid.
                //
                // Every scoring call failed that way the first time the pipeline
                // ran against the real stack rather than against a stub: 13,455
                // assessments degraded, 6,224 events dead-lettered, and a
                // one-to-one match between the upgrade warning and the rejection
                // in the scoring service's own log. Neither `ScoringClientTests`
                // nor `RiskAssessmentWorkflowIT` saw it, because
                // `com.sun.net.httpserver` answers the upgrade attempt by
                // ignoring it and reading the request as HTTP/1.1 - which is the
                // difference between a stub and the thing it stands for.
                //
                // The contract says nothing about HTTP/2 and neither service
                // gains anything from it at this volume. Asking for a protocol
                // the other side does not speak, on every request, is the cost.
                .version(HttpClient.Version.HTTP_1_1)
                // Never follow a redirect. This is an internal contract between
                // two services in one repository; a 3xx from it is not a route to
                // somewhere else, it is a misconfiguration, and following one
                // would send an account's history wherever it pointed.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());
        return factory;
    }

    @Bean
    RestClient scoringRestClient(ScoringClientProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(requestFactory(properties))
                .build();
    }

    /**
     * One breaker, shared by every thread that scores.
     *
     * <p>{@code Clock.systemUTC()} rather than an application-wide {@code Clock} bean: the breaker
     * takes a clock so a test can drive its open window without sleeping thirty seconds, and
     * introducing a shared bean for one collaborator would be a decision about every date in this
     * application taken for the sake of one timer.
     *
     * <p>The breaker is published as three gauges rather than as one number. An operator's question
     * is "is scoring cut off right now", and a single gauge encoding {@code CLOSED=0, OPEN=2} answers
     * it only for somebody who remembers the encoding — every dashboard panel and every alert rule
     * would carry the legend in a comment. One series per state, each 0 or 1, is three series
     * instead of one and reads as {@code sentinelflow_scoring_breaker_state{state="OPEN"} == 1}.
     */
    @Bean
    CircuitBreaker scoringCircuitBreaker(ScoringClientProperties properties, MeterRegistry meters) {
        CircuitBreaker breaker = new CircuitBreaker(
                ScoringClient.BREAKER_NAME,
                properties.circuitBreakerFailureThreshold(),
                properties.circuitBreakerOpenDuration(),
                Clock.systemUTC());

        for (CircuitBreaker.State state : CircuitBreaker.State.values()) {
            Gauge.builder(BREAKER_STATE_METRIC, breaker, observed -> observed.state() == state ? 1 : 0)
                    .tag("breaker", breaker.name())
                    .tag("state", state.name())
                    .description("1 on the state the circuit breaker in front of scoring is currently in, 0 otherwise")
                    .register(meters);
        }
        return breaker;
    }
}
