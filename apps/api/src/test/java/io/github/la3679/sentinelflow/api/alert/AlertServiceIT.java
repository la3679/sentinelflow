/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.la3679.sentinelflow.api.domain.Actor;
import io.github.la3679.sentinelflow.api.domain.ActorRole;
import io.github.la3679.sentinelflow.api.domain.AlertStatus;
import io.github.la3679.sentinelflow.api.persistence.entity.Alert;
import io.github.la3679.sentinelflow.api.service.exception.AlertNotFoundException;
import io.github.la3679.sentinelflow.api.service.exception.AlertVersionConflictException;
import io.github.la3679.sentinelflow.api.service.exception.IllegalAlertTransitionException;
import io.github.la3679.sentinelflow.api.support.AbstractPostgresTest;
import io.github.la3679.sentinelflow.api.support.SchemaFixtures;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Transitions against the real schema, because almost everything worth asserting here is enforced
 * by something this class does not own.
 *
 * <p>Whether the history row and the alert commit together is a transaction boundary. Whether a
 * terminal alert keeps its close time is a CHECK constraint. Whether the published version is the
 * one the row now holds depends on when the provider increments a {@code @Version} — which is the
 * kind of thing that is obviously right until it is written down. A test with a mocked repository
 * would assert that the service calls itself and would pass while every one of those was wrong.
 */
class AlertServiceIT extends AbstractPostgresTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private AlertService service;

    @Autowired
    private JdbcTemplate jdbc;

    private SchemaFixtures fixtures;
    private Actor analyst;

    @BeforeEach
    void setUp() {
        fixtures = new SchemaFixtures(jdbc);
        // The system principal is the only user V1 inserts, and this suite is
        // about transitions rather than about identity. Authentication supplies
        // a real operator later; what the service does with an actor is the
        // same either way, which is exactly why it takes one as a parameter.
        analyst = new Actor(fixtures.systemUserId(), ActorRole.ANALYST);
    }

    // ----------------------------------------------------------------------- //
    // The move itself
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("a legal move changes the status and leaves a live alert without a close time")
    void movesAndStaysOpen() {
        UUID alertId = newAlert();

        Alert moved = service.transition(alertId, AlertStatus.IN_REVIEW, 0, "Picked up", analyst, UUID.randomUUID());

        assertThat(moved.getStatus()).isEqualTo(AlertStatus.IN_REVIEW);
        Map<String, Object> row = alertRow(alertId);
        assertThat(row.get("status")).isEqualTo("IN_REVIEW");
        assertThat(row.get("closed_at"))
                .as("alerts_closed_at_consistent requires a live alert not to carry one")
                .isNull();
        assertThat((Long) row.get("version"))
                .as("the optimistic lock advances on every change, which is what makes it a token")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("a disposition stamps the close time the schema requires")
    void aDispositionCloses() {
        UUID alertId = newAlert();
        service.transition(alertId, AlertStatus.IN_REVIEW, 0, null, analyst, UUID.randomUUID());

        service.transition(alertId, AlertStatus.CONFIRMED_SUSPICIOUS, 1, "Card testing", analyst, UUID.randomUUID());

        Map<String, Object> row = alertRow(alertId);
        assertThat(row.get("status")).isEqualTo("CONFIRMED_SUSPICIOUS");
        assertThat(row.get("closed_at"))
                .as("without this, how long an investigation took is unanswerable for whichever "
                        + "rows the application forgot to stamp")
                .isNotNull();
    }

    @Test
    @DisplayName("every move is written to the history with its actor and both ends")
    void recordsTheMove() {
        UUID alertId = newAlert();
        UUID correlationId = UUID.randomUUID();

        service.transition(alertId, AlertStatus.IN_REVIEW, 0, "Picked up", analyst, correlationId);

        Map<String, Object> action = jdbc.queryForMap(
                "SELECT * FROM alert_actions WHERE alert_id = ? AND action_type = 'TRANSITIONED'", alertId);
        assertThat(action.get("previous_status")).isEqualTo("NEW");
        assertThat(action.get("new_status")).isEqualTo("IN_REVIEW");
        assertThat(action.get("actor_id")).isEqualTo(analyst.userId());
        assertThat(action.get("actor_role"))
                .as("the role as it was used, not as it is looked up later: a user's roles change "
                        + "and an audit trail has to say what was true at the time")
                .isEqualTo("ANALYST");
        assertThat(action.get("note")).isEqualTo("Picked up");
        assertThat(action.get("correlation_id")).isEqualTo(correlationId);
    }

    @Test
    @DisplayName("the alert.updated event carries both ends and the version after the change")
    void publishesTheChange() {
        UUID alertId = newAlert();

        service.transition(alertId, AlertStatus.IN_REVIEW, 0, null, analyst, UUID.randomUUID());

        Map<String, Object> event = jdbc.queryForMap(
                "SELECT * FROM outbox_events WHERE event_type = 'alert.updated' AND aggregate_id = ?", alertId);
        assertThat(event.get("aggregate_type")).isEqualTo("alert");
        assertThat(event.get("status")).isEqualTo("PENDING");
        assertThat(event.get("partition_key"))
                .as("keyed by the alert, so one alert's changes are ordered against each other")
                .isEqualTo(alertId.toString());

        JsonNode payload = MAPPER.readTree(String.valueOf(event.get("payload")));
        assertThat(payload.get("changeType").asString()).isEqualTo("STATUS_TRANSITION");
        assertThat(payload.get("previousStatus").asString())
                .as("a consumer that only ever saw the new state could not reconstruct the path, "
                        + "and the path is what an audit asks about")
                .isEqualTo("NEW");
        assertThat(payload.get("status").asString()).isEqualTo("IN_REVIEW");
        assertThat(payload.get("version").asLong())
                .as("the version after the change, read from the flushed row rather than guessed "
                        + "at by adding one to what was in memory")
                .isEqualTo(1L);
        assertThat(payload.get("actorRole").asString()).isEqualTo("ANALYST");
        assertThat(payload.get("assignee").isNull())
                .as("nullable and still present, so no consumer distinguishes unassigned from absent")
                .isTrue();
    }

    // ----------------------------------------------------------------------- //
    // The refusals
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("an illegal move is refused, and says what the caller may do instead")
    void refusesAnIllegalMove() {
        UUID alertId = newAlert();

        assertThatThrownBy(() -> service.transition(
                        alertId, AlertStatus.CONFIRMED_SUSPICIOUS, 0, null, analyst, UUID.randomUUID()))
                .isInstanceOf(IllegalAlertTransitionException.class)
                .hasMessageContaining("IN_REVIEW");

        assertThat(alertRow(alertId).get("status")).isEqualTo("NEW");
        assertThat(historyCount(alertId))
                .as("a refused transition writes nothing, including no audit row saying it was tried")
                .isZero();
    }

    @Test
    @DisplayName("a terminal alert refuses every move, and keeps its close time")
    void terminalMeansTerminal() {
        UUID alertId = newAlert();
        service.transition(alertId, AlertStatus.IN_REVIEW, 0, null, analyst, UUID.randomUUID());
        service.transition(alertId, AlertStatus.DISMISSED_FALSE_POSITIVE, 1, null, analyst, UUID.randomUUID());
        Object closedAt = alertRow(alertId).get("closed_at");

        assertThatThrownBy(
                        () -> service.transition(alertId, AlertStatus.IN_REVIEW, 2, null, analyst, UUID.randomUUID()))
                .isInstanceOf(IllegalAlertTransitionException.class)
                .hasMessageContaining("terminal");

        assertThat(alertRow(alertId).get("closed_at"))
                .as("reopening would clear this, and when the investigation ended would be lost")
                .isEqualTo(closedAt);
    }

    @Test
    @DisplayName("a caller working from a stale read is told, rather than overwriting")
    void refusesAStaleVersion() {
        UUID alertId = newAlert();
        service.transition(alertId, AlertStatus.IN_REVIEW, 0, null, analyst, UUID.randomUUID());

        // Version 0 is what a second analyst who opened the alert before the
        // first one acted still holds. Silently accepting it is how one
        // analyst's disposition replaces another's with neither of them knowing.
        assertThatThrownBy(
                        () -> service.transition(alertId, AlertStatus.ESCALATED, 0, null, analyst, UUID.randomUUID()))
                .isInstanceOf(AlertVersionConflictException.class)
                .hasMessageContaining("version 1");

        assertThat(alertRow(alertId).get("status")).isEqualTo("IN_REVIEW");
    }

    @Test
    @DisplayName("an alert that does not exist is a not-found, not a conflict")
    void refusesAnUnknownAlert() {
        assertThatThrownBy(() -> service.transition(
                        UUID.randomUUID(), AlertStatus.IN_REVIEW, 0, null, analyst, UUID.randomUUID()))
                .isInstanceOf(AlertNotFoundException.class);
    }

    // ----------------------------------------------------------------------- //
    // Fixtures and reads
    // ----------------------------------------------------------------------- //

    /** A NEW alert on its own transaction and assessment, at version 0. */
    private UUID newAlert() {
        UUID transactionId = fixtures.insertTransaction();
        return fixtures.insertAlert(transactionId, fixtures.insertAssessment(transactionId));
    }

    private Map<String, Object> alertRow(UUID alertId) {
        return jdbc.queryForMap("SELECT * FROM alerts WHERE id = ?", alertId);
    }

    private int historyCount(UUID alertId) {
        return jdbc.queryForObject("SELECT count(*) FROM alert_actions WHERE alert_id = ?", Integer.class, alertId);
    }
}
