/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for the SentinelFlow API.
 *
 * <p>This service owns transaction ingestion, the transactional outbox, alert lifecycle and audit.
 * Risk scoring is deliberately not here: it lives in the Python scoring service, which this
 * application calls. See {@code docs/adr/0002-monorepo-and-service-boundaries.md}.
 *
 * <p>SentinelFlow is an independent educational project operating on synthetic data. It makes no
 * real financial decisions.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class SentinelFlowApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(SentinelFlowApiApplication.class, args);
    }
}
