/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.risk.rules;

/**
 * The reason codes the deterministic ruleset can emit.
 *
 * <p><strong>A code is never renamed once emitted.</strong> It is persisted on every assessment and
 * published on {@code risk.assessed.v1}, so a rename silently breaks every historical query and
 * every saved analyst filter. Retiring a rule means the code stops being produced, not that it stops
 * existing.
 *
 * <p>These deliberately read like the codes {@code /v1/score} emits — {@code VELOCITY_5M_HIGH},
 * {@code NEW_DEVICE} — because an analyst reading an assessment should see one vocabulary rather
 * than two. What tells them apart is {@code source}: {@code RULE} here, {@code MODEL} there. A rule
 * can be read; a model score can only be attributed.
 *
 * <p>Every name satisfies the contract's {@code ^[A-Z][A-Z0-9_]{2,63}$}, and
 * {@code RuleCodeContractTests} asserts it rather than trusting that it looks right.
 */
public enum RuleCode {

    /** Several transactions inside a few minutes. The clearest velocity signal at this granularity. */
    VELOCITY_5M_HIGH,

    /** This amount against what this account usually spends, not against a global figure. */
    AMOUNT_RATIO_HIGH,

    /** A device the account has not used inside the lookback window. */
    NEW_DEVICE,

    /** A different country from the account's most recent transaction. */
    COUNTRY_CHANGE,

    /** The small hours, where retail activity is genuinely unusual. */
    OFF_HOURS,

    /** A single transaction moving a large share of the balance. */
    BALANCE_DRAIN_HIGH,

    /** Several distinct merchants inside an hour, which is what card testing looks like. */
    DISTINCT_MERCHANTS_1H_HIGH
}
