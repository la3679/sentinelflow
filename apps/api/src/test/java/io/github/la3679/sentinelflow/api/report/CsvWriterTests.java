/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The escaping, and the reason it exists.
 *
 * <p>Half of these are RFC 4180 mechanics and half are a security control. The second half is what
 * makes this a unit test with more cases than the class has branches: a formula-leading character
 * that slips through produces a file that runs somebody else's code on an operations team's machine,
 * and the failure is silent at every stage before that.
 */
class CsvWriterTests {

    // ----------------------------------------------------------------------- //
    // Formula injection
    // ----------------------------------------------------------------------- //

    @ParameterizedTest
    @ValueSource(
            strings = {
                "=1+1",
                "+1",
                "-1+1",
                "@SUM(A1)",
                "\tleading tab",
                "\rleading carriage return",
                "=HYPERLINK(\"https://example.invalid\"&A1,\"click\")",
                "=cmd|'/c calc'!A1"
            })
    @DisplayName("a cell that could be a formula is prefixed so a spreadsheet reads it as text")
    void neutralisesFormulas(String dangerous) {
        // Unquoted first, because some of these also need quoting for reasons
        // that have nothing to do with formulas - a comma, a quote, a carriage
        // return - and the property under test is about what the spreadsheet
        // sees in the cell, not about the field's outer syntax.
        assertThat(fieldContent(CsvWriter.cell(dangerous)))
                .as("%s must not be evaluated when the file is opened", dangerous)
                .startsWith("'" + dangerous.charAt(0));
    }

    /** What a conforming reader puts in the cell: the field with its quoting undone. */
    private static String fieldContent(String field) {
        if (field.startsWith("\"") && field.endsWith("\"") && field.length() >= 2) {
            return field.substring(1, field.length() - 1).replace("\"\"", "\"");
        }
        return field;
    }

    @Test
    @DisplayName("a negative number is prefixed too, and that is the deliberate cost")
    void negativeNumbersArePrefixed() {
        // -2+3+cmd|'/c calc'!A0 is a formula that begins like a number, so the
        // leading '-' cannot be allowed through on the grounds that some values
        // starting with it are arithmetic. A negative number reads as text in
        // the export; a command does not execute.
        assertThat(CsvWriter.cell("-42.50")).isEqualTo("'-42.50");
    }

    @Test
    @DisplayName("a dangerous character anywhere but the first position is left alone")
    void onlyTheLeadingCharacterMatters() {
        // A spreadsheet decides what a cell is from where it starts. Prefixing
        // on any occurrence would put an apostrophe in front of every email
        // address and every reason code containing a hyphen.
        assertThat(CsvWriter.cell("VELOCITY_5M_HIGH")).isEqualTo("VELOCITY_5M_HIGH");
        assertThat(CsvWriter.cell("analyst@example.invalid")).isEqualTo("analyst@example.invalid");
        assertThat(CsvWriter.cell("2+2 was the amount")).isEqualTo("2+2 was the amount");
    }

    @Test
    @DisplayName("the prefix goes inside the quotes, not outside them")
    void thePrefixIsInsideTheQuoting() {
        // Outside, the field would begin with an unquoted apostrophe followed by
        // a quote, which is not a field any parser can read.
        assertThat(CsvWriter.cell("=1,2")).isEqualTo("\"'=1,2\"");
    }

    // ----------------------------------------------------------------------- //
    // RFC 4180
    // ----------------------------------------------------------------------- //

    @Test
    @DisplayName("a field containing a comma, a quote or a newline is quoted")
    void quotesWhatMustBeQuoted() {
        assertThat(CsvWriter.cell("one,two")).isEqualTo("\"one,two\"");
        assertThat(CsvWriter.cell("line\nbreak")).isEqualTo("\"line\nbreak\"");
        assertThat(CsvWriter.cell("a\"b")).isEqualTo("\"a\"\"b\"");
    }

    @Test
    @DisplayName("a quote inside a quoted field is doubled, not escaped")
    void doublesInnerQuotes() {
        // RFC 4180 has no backslash escape. A parser reading one would end the
        // field at the first inner quote and put the rest in the next column.
        assertThat(CsvWriter.cell("he said \"card testing\"")).isEqualTo("\"he said \"\"card testing\"\"\"");
    }

    @Test
    @DisplayName("a leading or trailing space survives the round trip")
    void quotesSurroundingSpace() {
        // A reference with a stray space is a reference somebody will fail to
        // match, and unquoted whitespace is exactly what a reader trims.
        assertThat(CsvWriter.cell(" ALT-0001")).isEqualTo("\" ALT-0001\"");
        assertThat(CsvWriter.cell("ALT-0001 ")).isEqualTo("\"ALT-0001 \"");
    }

    @Test
    @DisplayName("null and empty are an empty field, never the word null")
    void nullIsEmpty() {
        assertThat(CsvWriter.cell(null)).isEmpty();
        assertThat(CsvWriter.cell("")).isEmpty();
    }

    @Test
    @DisplayName("a row is comma-separated and ends with CRLF")
    void writesARow() {
        // CRLF because RFC 4180 says so and because Excel expects it. A lone LF
        // is read correctly by most tools and by not all of them.
        assertThat(CsvWriter.row(List.of("ALT-0001", "NEW", "HIGH"))).isEqualTo("ALT-0001,NEW,HIGH\r\n");
    }

    @Test
    @DisplayName("a row with a null cell keeps its column count")
    void nullCellsKeepTheirColumn() {
        // A row that dropped a null would shift every column after it, and the
        // file would parse without complaint into the wrong shape.
        assertThat(CsvWriter.row(Arrays.asList("ALT-0001", null, "HIGH"))).isEqualTo("ALT-0001,,HIGH\r\n");
    }
}
