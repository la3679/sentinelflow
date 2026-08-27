/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.scoring.client;

import java.net.http.HttpClient;
import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import io.github.la3679.sentinelflow.api.resilience.CircuitBreaker;

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
     */
    @Bean
    CircuitBreaker scoringCircuitBreaker(ScoringClientProperties properties) {
        return new CircuitBreaker(
                ScoringClient.BREAKER_NAME,
                properties.circuitBreakerFailureThreshold(),
                properties.circuitBreakerOpenDuration(),
                Clock.systemUTC());
    }
}
