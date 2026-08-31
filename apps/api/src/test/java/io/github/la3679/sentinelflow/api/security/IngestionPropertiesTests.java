/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The ingestion key is validated at startup, because startup is the only time anybody is looking.
 *
 * <p>Every failure here is one that would otherwise not be a failure at all: an absent key would
 * leave the endpoint open, and a four-character one would authenticate perfectly.
 */
class IngestionPropertiesTests {

    private static final String VALID = "a-key-that-is-long-enough-to-be-one";

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    @DisplayName("an absent key fails startup, because the alternative is an open write endpoint")
    void refusesAnAbsentKey(String absent) {
        assertThatThrownBy(() -> new IngestionProperties(absent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required and has no default")
                .hasMessageContaining("SENTINELFLOW_INGEST_API_KEY");
    }

    @Test
    @DisplayName("a key shorter than the floor fails startup, because nothing downstream would report it")
    void refusesAShortKey() {
        assertThatThrownBy(() -> new IngestionProperties("too-short"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs at least " + IngestionProperties.MINIMUM_KEY_LENGTH);
    }

    @Test
    @DisplayName("the refusal reports the length and never the key, because that message reaches a log")
    void neverEchoesTheKey() {
        String secret = "short-secret";

        assertThatThrownBy(() -> new IngestionProperties(secret))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(secret);
    }

    @Test
    @DisplayName("a key at the floor is accepted, so the boundary is inclusive rather than off by one")
    void acceptsAKeyExactlyAtTheFloor() {
        String exactly = "x".repeat(IngestionProperties.MINIMUM_KEY_LENGTH);

        assertThat(new IngestionProperties(exactly).apiKey()).isEqualTo(exactly);
    }

    @Test
    @DisplayName("the bytes are UTF-8, which is what the constant-time comparison hashes")
    void exposesTheKeyAsUtf8Bytes() {
        assertThat(new IngestionProperties(VALID).apiKeyBytes())
                .isEqualTo(VALID.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
