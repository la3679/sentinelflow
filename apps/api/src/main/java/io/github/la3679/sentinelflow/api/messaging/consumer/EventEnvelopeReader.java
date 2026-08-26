/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.messaging.consumer;

import org.springframework.stereotype.Component;

import io.github.la3679.sentinelflow.api.domain.DlqFailureClass;
import io.github.la3679.sentinelflow.api.messaging.EventEnvelope;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

/**
 * Reads what arrived on a topic, and classifies it when it cannot.
 *
 * <p><strong>Tolerant of unknown fields, deliberately and only here.</strong> ADR-0006 §3 splits
 * this in two: a <em>configuration</em> model rejects a typo loudly, because a misspelt setting is a
 * defect; an <em>event consumer</em> ignores fields it does not know, because a producer that has
 * added an optional field is doing something the compatibility policy explicitly allows. The
 * application-wide mapper is configured {@code fail-on-unknown-properties: true} for the API
 * boundary, which is right there and wrong here — so this reads through readers that switch that one
 * feature off, rather than through a second mapper that would drift from the first on every other
 * setting.
 *
 * <p><strong>Every failure comes back classified.</strong> A parse failure is not retryable: the
 * bytes will be identical next time, so retrying costs the whole partition behind it and changes
 * nothing (ADR-0006 §4).
 */
@Component
public class EventEnvelopeReader {

    private final ObjectReader envelopeReader;
    private final ObjectReader lenient;

    public EventEnvelopeReader(ObjectMapper objectMapper) {
        this.lenient = objectMapper.reader().without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.envelopeReader = lenient.forType(EventEnvelope.class);
    }

    /**
     * @throws NonRetryableEventException classified {@link DlqFailureClass#MALFORMED_PAYLOAD} if the
     *     text is not a readable envelope
     */
    public EventEnvelope readEnvelope(String json) {
        if (json == null || json.isBlank()) {
            throw new NonRetryableEventException(DlqFailureClass.MALFORMED_PAYLOAD, "Record had no value");
        }
        try {
            EventEnvelope envelope = envelopeReader.readValue(json);
            if (envelope == null || envelope.eventId() == null) {
                // The deduplication key. Without it there is no way to process
                // this at most once, so it cannot be processed at all.
                throw new NonRetryableEventException(
                        DlqFailureClass.SCHEMA_VALIDATION_FAILED, "Envelope carried no eventId");
            }
            return envelope;
        } catch (JacksonException unreadable) {
            throw new NonRetryableEventException(
                    DlqFailureClass.MALFORMED_PAYLOAD, "Record is not a readable event envelope", unreadable);
        }
    }

    /**
     * Reads an envelope's payload as the type its {@code eventType} promises.
     *
     * @throws NonRetryableEventException classified {@link DlqFailureClass#SCHEMA_VALIDATION_FAILED}
     *     if the payload is not that shape
     */
    public <T> T readPayload(JsonNode payload, Class<T> type) {
        if (payload == null || payload.isNull()) {
            throw new NonRetryableEventException(
                    DlqFailureClass.SCHEMA_VALIDATION_FAILED, "Envelope carried no payload");
        }
        try {
            return lenient.forType(type).readValue(payload);
        } catch (JacksonException wrongShape) {
            throw new NonRetryableEventException(
                    DlqFailureClass.SCHEMA_VALIDATION_FAILED,
                    "Payload does not match " + type.getSimpleName(),
                    wrongShape);
        }
    }
}
