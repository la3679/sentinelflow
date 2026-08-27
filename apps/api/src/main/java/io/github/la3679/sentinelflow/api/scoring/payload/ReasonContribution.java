/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.scoring.payload;

import java.math.BigDecimal;

/**
 * One reason the model's score is what it is, as the scoring service reports it.
 *
 * <p>A code and a signed contribution, not free text: the console groups and filters on the code,
 * and an analyst's written justification has to survive the model being replaced.
 *
 * <p><strong>The contribution is not on the 0-to-100 scale and does not sum to the score.</strong>
 * It is the linear model's own decomposition on the log-odds scale before calibration, so it
 * explains the ranking. The contract says as much: "Units are the model's own and are only
 * comparable within one {@code modelVersion}." Adding these up and presenting the total as a score
 * would be a number an analyst could not check.
 *
 * @param code a stable identifier such as {@code VELOCITY_1M_HIGH}. Never renamed once emitted.
 * @param contribution signed; positive pushed the score up.
 */
public record ReasonContribution(String code, BigDecimal contribution) {}
