/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.persistence.converter;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import io.github.la3679.sentinelflow.api.domain.AggregateType;

/**
 * Stores {@link AggregateType} in the column using the contract's spelling rather than Java's.
 *
 * <p>{@code @Enumerated(STRING)} would write {@code TRANSACTION}. The event envelope schema
 * says {@code transaction}, the {@code CHECK} constraint says the same, and the contract wins:
 * a column whose values a consumer cannot match against the envelope it received is a column that
 * has to be translated at every read.
 *
 * <p>An unrecognised value from the database throws. A row holding a value no constant covers means
 * either the constraint was dropped or the schema moved ahead of this code, and both are worth
 * failing on rather than mapping to null.
 */
@Converter(autoApply = true)
public class AggregateTypeConverter implements AttributeConverter<AggregateType, String> {

    @Override
    public String convertToDatabaseColumn(AggregateType attribute) {
        return attribute == null ? null : attribute.wireValue();
    }

    @Override
    public AggregateType convertToEntityAttribute(String dbData) {
        return dbData == null ? null : AggregateType.fromWireValue(dbData);
    }
}
