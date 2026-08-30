/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;

import io.github.la3679.sentinelflow.api.support.AbstractPostgresTest;
import io.github.la3679.sentinelflow.api.web.CorrelationIdFilter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * That the container's log format is JSON, and that the correlation id survives into it.
 *
 * <p><strong>Configured the way the container configures it.</strong> {@code compose.yaml} sets
 * {@code LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs} and nothing sets it locally, because Boot reads an
 * unset property as "the ordinary pattern encoder" and an empty string as a format name it cannot
 * resolve — so there is no value meaning "plain" that could be written in {@code application.yaml}.
 * That split is only worth having if the ecs half actually renders, and this is what says it does.
 *
 * <p><strong>The correlation id is the assertion that matters most.</strong> A JSON line nothing can
 * correlate is a prettier version of a line nobody can follow: the whole reason the format is worth
 * changing is that a collector can index {@code correlationId} and return every line belonging to
 * one request. {@code CorrelationIdFilter} puts it in the MDC, and Boot's ECS formatter lifts MDC
 * entries onto the object — but "lifts them onto the object" is a claim about a library version, and
 * this is what stops it being an assumption.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = "logging.structured.format.console=ecs")
class StructuredLogFormatIT extends AbstractPostgresTest {

    private static final Logger log = LoggerFactory.getLogger(StructuredLogFormatIT.class);

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @AfterEach
    void clearMdc() {
        // Listener and request threads are pooled, and a value left behind
        // attributes the next piece of work's logs to this test's request.
        MDC.clear();
    }

    @Test
    @DisplayName("a line renders as one JSON object carrying the ECS fields")
    void rendersEcs(CapturedOutput output) {
        log.info("a line this test wrote");

        JsonNode line = lastJsonLine(output);

        // ECS nests: the schema's dotted names are objects on the wire, so
        // `log.level` is /log/level. Asserting on the nesting rather than on a
        // flat key is asserting on what a collector will actually receive.
        assertThat(at(line, "/message")).isEqualTo("a line this test wrote");
        assertThat(at(line, "/log/level")).isEqualTo("INFO");
        assertThat(at(line, "/log/logger")).isEqualTo(StructuredLogFormatIT.class.getName());
        assertThat(at(line, "/@timestamp")).isNotBlank();
        assertThat(at(line, "/ecs/version"))
                .as("the field a collector reads to know which schema the rest of the object is in")
                .isNotBlank();
        assertThat(at(line, "/service/name"))
                .as("what tells this service's lines from the scoring service's in one collector")
                .isEqualTo("sentinelflow-api");
        assertThat(at(line, "/service/version"))
                .as("SENTINELFLOW_GIT_SHA, which compose passes so a line can be traced back to the "
                        + "build that wrote it. Unset outside the container, and `unknown` is the "
                        + "honest answer rather than a blank")
                .isEqualTo("unknown");
    }

    @Test
    @DisplayName("the correlation id reaches the object, so one request can be pulled out of a day")
    void carriesTheCorrelationId(CapturedOutput output) {
        UUID correlationId = UUID.fromString("0198f0a1-2b3c-7d4e-8f90-aaaaaaaaaaaa");
        MDC.put(CorrelationIdFilter.MDC_KEY, correlationId.toString());

        log.info("a line written while handling a request");

        JsonNode line = lastJsonLine(output);
        // Top level rather than nested: Boot lifts MDC entries onto the object
        // as they are, which is what makes `correlationId: "..."` a query a
        // collector can run without a mapping written by hand.
        assertThat(at(line, "/" + CorrelationIdFilter.MDC_KEY)).isEqualTo(correlationId.toString());
    }

    /** One field by JSON pointer, or a failure that shows the whole line. */
    private static String at(JsonNode line, String pointer) {
        JsonNode value = line.at(pointer);
        assertThat(value.isMissingNode()).as("no %s in %s", pointer, line).isFalse();
        return value.asString();
    }

    /**
     * The last thing written, parsed.
     *
     * <p>The last line rather than the first: the context is cached across the suite, so what is in
     * this stream ahead of the line under test depends on which tests ran before it. Parsing is the
     * assertion — a line that is not JSON throws here rather than passing a substring check.
     */
    private static JsonNode lastJsonLine(CapturedOutput output) {
        String last = output.getAll()
                .lines()
                .map(String::trim)
                .filter(line -> line.startsWith("{"))
                .reduce((earlier, later) -> later)
                .orElseThrow(() -> new AssertionError("nothing was written as JSON:\n" + output.getAll()));
        return MAPPER.readTree(last);
    }
}
