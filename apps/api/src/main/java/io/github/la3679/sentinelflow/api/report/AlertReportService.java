/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.report;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.github.la3679.sentinelflow.api.domain.AlertPriority;
import io.github.la3679.sentinelflow.api.domain.AlertStatus;
import io.github.la3679.sentinelflow.api.domain.RiskBand;
import io.github.la3679.sentinelflow.api.persistence.entity.Alert;
import io.github.la3679.sentinelflow.api.persistence.repository.AlertRepository;

/**
 * What the operations team is asked at the end of a week.
 *
 * <h2>Two shapes, and why they are different endpoints</h2>
 *
 * A <strong>summary</strong> is an aggregate whose size does not depend on the data: six statuses,
 * four priorities, four bands. It needs no paging because it cannot grow, and paging it would be a
 * parameter nobody could use.
 *
 * <p>An <strong>export</strong> is one row per alert and grows without limit, so it is capped. Not
 * paged: a report a person opens in a spreadsheet is a file rather than a cursor, and asking an
 * analyst to stitch four pages together in Excel is how a report becomes wrong. The cap is the
 * honest alternative — a bounded window, and a refusal when the window holds more than that, which
 * says exactly what to narrow.
 *
 * <h2>Every number here is counted, never estimated</h2>
 *
 * The counts come from the rows in the window. Nothing is sampled and nothing is cached, because a
 * figure in an operations report that is quietly approximate is worse than no figure — it is one
 * somebody will quote.
 */
@Service
public class AlertReportService {

    /**
     * The most rows one export may contain.
     *
     * <p>Ten thousand is roughly what a spreadsheet handles comfortably and is far more than any
     * review window this demo produces. It exists so the endpoint cannot become a way to pull the
     * whole table into memory in one request — {@code .claude/rules/java.md} calls an endpoint whose
     * result grows with the dataset a denial-of-service primitive, and an export is the most
     * tempting place to make that exception.
     */
    public static final int MAX_EXPORT_ROWS = 10_000;

    private final AlertRepository alerts;

    public AlertReportService(AlertRepository alerts) {
        this.alerts = alerts;
    }

    /**
     * Counts over one window, by every dimension an operations screen asks about.
     *
     * @param from inclusive lower bound on {@code created_at}
     * @param to exclusive upper bound, so two adjacent windows neither overlap nor drop a row
     */
    @Transactional(readOnly = true)
    public AlertSummary summary(Instant from, Instant to) {
        Map<AlertStatus, Long> byStatus = new EnumMap<>(AlertStatus.class);
        Map<AlertPriority, Long> byPriority = new EnumMap<>(AlertPriority.class);
        Map<RiskBand, Long> byBand = new EnumMap<>(RiskBand.class);

        // Every key present, including the zeroes. A missing key and a zero are
        // the same fact and a client should not have to know that; a chart with
        // a gap where CRITICAL should be reads as missing data rather than as
        // none.
        for (AlertStatus status : AlertStatus.values()) {
            byStatus.put(status, 0L);
        }
        for (AlertPriority priority : AlertPriority.values()) {
            byPriority.put(priority, 0L);
        }
        for (RiskBand band : RiskBand.values()) {
            byBand.put(band, 0L);
        }

        alerts.countByStatus(from, to).forEach(count -> byStatus.put(count.status(), count.total()));
        alerts.countByPriority(from, to).forEach(count -> byPriority.put(count.priority(), count.total()));
        alerts.countByBand(from, to).forEach(count -> byBand.put(count.band(), count.total()));

        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        long open = alerts.countOpen(from, to);

        return new AlertSummary(from, to, total, open, total - open, byStatus, byPriority, byBand);
    }

    /**
     * One export window as CSV text.
     *
     * <p>Built in memory rather than streamed. At the cap this is a few megabytes, which a request
     * thread can hold; streaming would be the right answer at a hundred times the size and is the
     * wrong complexity at this one. The cap is what makes that true, so the two decisions are the
     * same decision.
     *
     * @throws ExportTooLargeException if the window holds more rows than the cap
     */
    @Transactional(readOnly = true)
    public String exportCsv(Instant from, Instant to) {
        long rows = alerts.countInWindow(from, to);
        if (rows > MAX_EXPORT_ROWS) {
            throw new ExportTooLargeException(rows, MAX_EXPORT_ROWS);
        }

        // One more than the cap would be pointless: the count above already
        // refused anything larger, and this reads the window it approved.
        List<Alert> window = alerts.findWindow(from, to, Limit.of(MAX_EXPORT_ROWS));

        StringBuilder csv = new StringBuilder();
        csv.append(CsvWriter.row(List.of(
                "alertReference",
                "status",
                "priority",
                "riskBand",
                "finalScore",
                "assigneeId",
                "summary",
                "createdAt",
                "closedAt")));

        for (Alert alert : window) {
            List<String> cells = new ArrayList<>();
            cells.add(alert.getAlertReference());
            cells.add(alert.getStatus().name());
            cells.add(alert.getPriority().name());
            cells.add(alert.getRiskBand().name());
            cells.add(alert.getFinalScore().toPlainString());
            cells.add(
                    alert.getAssigneeId() == null ? null : alert.getAssigneeId().toString());
            // The one free-text column, and the reason CsvWriter exists.
            cells.add(alert.getSummary());
            cells.add(alert.getCreatedAt().toString());
            cells.add(alert.getClosedAt() == null ? null : alert.getClosedAt().toString());
            csv.append(CsvWriter.row(cells));
        }
        return csv.toString();
    }

    /**
     * The window's counts, by every dimension.
     *
     * <p>{@code open} is derived from {@code closed_at} rather than from the status list, because
     * "which statuses mean open" is a fact about the state machine and duplicating it here would be
     * a second place to change when a status is added.
     */
    public record AlertSummary(
            Instant from,
            Instant to,
            long total,
            long open,
            long closed,
            Map<AlertStatus, Long> byStatus,
            Map<AlertPriority, Long> byPriority,
            Map<RiskBand, Long> byBand) {}

    /** The window holds more rows than one file may carry. */
    public static class ExportTooLargeException extends RuntimeException {

        private final long rows;
        private final int limit;

        public ExportTooLargeException(long rows, int limit) {
            super("The window holds " + rows + " alerts and an export carries at most " + limit);
            this.rows = rows;
            this.limit = limit;
        }

        public long rows() {
            return rows;
        }

        public int limit() {
            return limit;
        }
    }
}
