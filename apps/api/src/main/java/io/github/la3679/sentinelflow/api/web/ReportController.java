/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.web;

import java.time.Duration;
import java.time.Instant;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.la3679.sentinelflow.api.report.AlertReportService;
import io.github.la3679.sentinelflow.api.service.exception.InvalidWindowException;

/**
 * Operational reports over the alert queue.
 *
 * <h2>Readable by every authenticated role</h2>
 *
 * Including {@code AUDITOR}, which is most of the point of that role: somebody who can read what the
 * team did and change none of it. Neither endpoint here mutates anything.
 *
 * <h2>The window is required, and that is not friction for its own sake</h2>
 *
 * A report with no window is a report over the whole table, which grows without limit. Defaulting it
 * to "the last seven days" would be a number invented here rather than asked for, and a client that
 * did not notice would quote a figure about a period it did not choose. Asking for both ends makes
 * the period part of the answer.
 */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    /**
     * The longest window either report accepts.
     *
     * <p>A year, which is longer than any review period this demo has data for and short enough that
     * the count behind a summary stays a scan of a bounded slice. The export has its own row cap on
     * top; this bounds the query, that bounds the file.
     */
    static final Duration MAX_WINDOW = Duration.ofDays(366);

    private final AlertReportService reports;

    public ReportController(AlertReportService reports) {
        this.reports = reports;
    }

    /**
     * Counts over one window, by status, priority and band.
     *
     * <p>Not paged, and deliberately: six statuses, four priorities and four bands are the whole of
     * it however many alerts the window holds. A page parameter on a fixed-size response would be
     * one nobody could use.
     */
    @GetMapping("/alert-summary")
    AlertReportService.AlertSummary alertSummary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {

        return reports.summary(from, requireValidWindow(from, to));
    }

    /**
     * One window of alerts as a CSV file.
     *
     * <p><strong>{@code text/csv} with a filename</strong>, so a browser downloads it rather than
     * rendering it, and so the file an analyst finds in their downloads folder says which window it
     * covers. A file called {@code export.csv} is one nobody can identify a week later.
     *
     * <p>Every cell goes through {@code CsvWriter}, which neutralises a value a spreadsheet would
     * treat as a formula. The alert summary is generated text, but it is generated from a
     * transaction reference that arrived through an open ingestion endpoint — so a cell in this file
     * can contain characters somebody outside the system chose.
     */
    @GetMapping(path = "/alerts.csv", produces = "text/csv")
    ResponseEntity<String> exportAlerts(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {

        String csv = reports.exportCsv(from, requireValidWindow(from, to));

        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename("sentinelflow-alerts-%s-to-%s.csv".formatted(from, to))
                                .build()
                                .toString())
                .body(csv);
    }

    /**
     * Both ends of the window, checked together.
     *
     * <p>An inverted window silently returns nothing, which reads as "there were no alerts" and is
     * the worst answer a report can give. An unbounded one is a scan of the whole table. Neither is
     * something a caller can be left to get right.
     */
    private static Instant requireValidWindow(Instant from, Instant to) {
        if (!to.isAfter(from)) {
            throw new InvalidWindowException("`to` must be after `from`; an inverted window returns nothing at all");
        }
        if (Duration.between(from, to).compareTo(MAX_WINDOW) > 0) {
            throw new InvalidWindowException("the window may not exceed " + MAX_WINDOW.toDays() + " days");
        }
        return to;
    }
}
