/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.seed;

/**
 * How much demo data to load.
 *
 * <p>Three sizes rather than a free-form count, so that "the CI dataset" and "the demo dataset" are
 * named things a test can assert against and a reader can reason about, instead of numbers that
 * drift apart between a workflow file and a README.
 *
 * <p>Each profile sizes both halves of a dataset: the parties, and the traffic the scenario
 * generator lays over them. They are one enum rather than two because a transaction count that does
 * not match its account count produces a dataset nobody wants — a hundred thousand transactions over
 * twenty accounts is not a bigger demo, it is a different and less realistic one.
 *
 * <p><strong>Transaction counts are the background only.</strong> Each planted scenario adds between
 * one and seven transactions of its own, so the total is a little above {@link #transactions}. The
 * manifest records what was actually generated rather than what was asked for.
 */
public enum SeedProfile {

    /**
     * Small enough that a CI job pays no meaningful time for it.
     *
     * <p>Twelve scenarios rather than six, so every one of the six shapes appears twice: a suite
     * that asserts a shape exists must not depend on a draw going a particular way.
     */
    CI(20, 8, 1, 200, 12),

    /** Enough breadth that a demo has variety without a queue nobody can read. */
    DEMO(200, 40, 2, 2_000, 30),

    /** Large enough for local query plans to be worth looking at. */
    LOCAL(2_000, 150, 3, 20_000, 200);

    private final int customers;
    private final int merchants;
    private final int accountsPerCustomer;
    private final int transactions;
    private final int scenarios;

    SeedProfile(int customers, int merchants, int accountsPerCustomer, int transactions, int scenarios) {
        this.customers = customers;
        this.merchants = merchants;
        this.accountsPerCustomer = accountsPerCustomer;
        this.transactions = transactions;
        this.scenarios = scenarios;
    }

    public int customers() {
        return customers;
    }

    public int merchants() {
        return merchants;
    }

    public int accountsPerCustomer() {
        return accountsPerCustomer;
    }

    public int accounts() {
        return customers * accountsPerCustomer;
    }

    /** How many ordinary background transactions to generate. */
    public int transactions() {
        return transactions;
    }

    /** How many suspicious shapes to plant. Each contributes one or more transactions of its own. */
    public int scenarios() {
        return scenarios;
    }
}
