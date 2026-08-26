/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.seed.scenario;

/**
 * The patterns the generator plants in otherwise ordinary traffic.
 *
 * <p>Each is a <em>shape</em> — several transactions in a particular arrangement — rather than a
 * single suspicious-looking row. That is the point of generating data at all: a rule or a model that
 * only has to notice one large amount can be written without any of this, and would tell you nothing
 * about whether the pipeline works. Every shape here needs history to see, which is exactly what
 * ADR-0008 says the account context carries and what the velocity features are for.
 *
 * <p><strong>These labels never enter the database.</strong> The generator knows which transactions
 * it planted; the running system does not, and must not. A label column on {@code transactions}
 * would be information that only exists after the fact, sitting next to the row a model is asked to
 * score — the textbook leak. What the operational schema records is an analyst's verdict, in
 * {@code analyst_feedback}, which is a different thing arrived at honestly.
 *
 * <p>The names are descriptive of the shape rather than of a verdict. {@link #NORMAL} traffic can
 * still be fraudulent in the real world and a planted shape can be a customer on holiday; the
 * generator is producing patterns worth detecting, not ground truth about intent.
 */
public enum ScenarioType {

    /** Ordinary background traffic: familiar merchants, familiar devices, unremarkable amounts. */
    NORMAL,

    /** Several transactions on one account inside a minute or two. Needs per-account ordering. */
    VELOCITY_BURST,

    /** One purchase far above what this account normally spends. Needs an account baseline. */
    AMOUNT_SPIKE,

    /** A run of tiny card-not-present authorisations, then one large one. Needs the run to be seen. */
    CARD_TESTING,

    /** Two card-present purchases in different countries, too close together to be both. */
    GEO_IMPROBABLE,

    /** Withdrawals and transfers that empty most of a balance in under an hour. */
    ACCOUNT_DRAIN,

    /** A purchase in the small hours from a device this account has never used. */
    OFF_HOURS_NEW_DEVICE
}
