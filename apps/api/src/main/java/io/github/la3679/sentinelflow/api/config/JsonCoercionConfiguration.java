/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

/**
 * Stops Jackson quietly turning a JSON number into a string.
 *
 * <p>ADR-0007 requires that money crosses every boundary as a decimal <em>string</em>, because
 * {@code JSON.parse} produces a {@code double} and a JSON number is therefore rounded by every
 * JavaScript consumer before application code sees it. The contract says so, the schema's
 * {@code money} pattern says so, and {@code AmountRequest.value} is a {@code String} for the same
 * reason.
 *
 * <p>None of that is enough on its own. Jackson's default coercion happily reads
 * {@code "value": 1249.99} into a {@code String} field, producing {@code "1249.99"} — and the
 * pattern then matches, the request is accepted, and the one rule the design exists to protect has
 * been broken silently by the parser before any of this project's own code ran. Worse, the value
 * that arrives is whatever the sender's own {@code double} rounded to, so it is not even reliably
 * the number they meant.
 *
 * <p>Failing the coercion turns that into a {@code 400} naming the field. It applies to every
 * textual field, not only money: a client sending {@code "originCountry": 44} has a bug, and
 * accepting {@code "44"} would hide it.
 *
 * <p>Found by a test that sent an amount as a number and got {@code 202}.
 */
@Configuration(proxyBeanMethods = false)
public class JsonCoercionConfiguration {

    @Bean
    JsonMapperBuilderCustomizer strictScalarCoercion() {
        return builder -> builder.withCoercionConfig(LogicalType.Textual, config -> {
            config.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
            config.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
            config.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        });
    }
}
