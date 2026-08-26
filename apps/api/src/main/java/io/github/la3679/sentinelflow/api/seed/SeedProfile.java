/* SPDX-License-Identifier: Apache-2.0 */
package io.github.la3679.sentinelflow.api.seed;

/**
 * How much demo data to load.
 *
 * <p>Three sizes rather than a free-form count, so that "the CI dataset" and "the demo dataset" are
 * named things a test can assert against and a reader can reason about, instead of numbers that
 * drift apart between a workflow file and a README.
 *
 * <p>Counts are parties only. Transactions, scenarios and labelled suspicious patterns arrive with
 * the generator in Phase 4 and are driven by these same parties.
 */
public enum SeedProfile {

    /** Small enough that a CI job pays no meaningful time for it. */
    CI(20, 8, 1),

    /** Enough breadth that a demo has variety without a queue nobody can read. */
    DEMO(200, 40, 2),

    /** Large enough for local query plans to be worth looking at. */
    LOCAL(2_000, 150, 3);

    private final int customers;
    private final int merchants;
    private final int accountsPerCustomer;

    SeedProfile(int customers, int merchants, int accountsPerCustomer) {
        this.customers = customers;
        this.merchants = merchants;
        this.accountsPerCustomer = accountsPerCustomer;
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
}
