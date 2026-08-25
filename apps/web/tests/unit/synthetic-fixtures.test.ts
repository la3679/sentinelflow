import { describe, expect, it } from "vitest";

import { getOverview, listAlerts, listTransactions } from "@/mocks/mockApi";

const ALL_PAGE = { page: 1, pageSize: 500 } as const;

describe("synthetic fixtures", () => {
  it("uses only fictional identifiers", () => {
    const { items } = listTransactions({ ...ALL_PAGE });
    expect(items.length).toBeGreaterThan(0);
    for (const tx of items) {
      expect(tx.accountId).toMatch(/^ACC-\d+$/);
      expect(tx.merchantId).toMatch(/^MER-\d+$/);
    }
  });

  it("represents money as decimal strings with an explicit currency", () => {
    const { items } = listTransactions({ ...ALL_PAGE });
    for (const tx of items) {
      expect(typeof tx.money.amount).toBe("string");
      expect(tx.money.amount).toMatch(/^\d+\.\d{2}$/);
      expect(tx.money.currency).toMatch(/^[A-Z]{3}$/);
    }
  });

  it("contains nothing resembling a card number or national identifier", () => {
    const serialized = JSON.stringify({
      transactions: listTransactions({ ...ALL_PAGE }),
      alerts: listAlerts({ ...ALL_PAGE }),
      overview: getOverview(),
    });
    // 13-19 consecutive digits would look like a PAN; NNN-NN-NNNN like an SSN.
    expect(serialized).not.toMatch(/\d{13,19}/);
    expect(serialized).not.toMatch(/\b\d{3}-\d{2}-\d{4}\b/);
  });

  it("is deterministic across repeated reads", () => {
    expect(listAlerts({ ...ALL_PAGE })).toEqual(listAlerts({ ...ALL_PAGE }));
    expect(getOverview()).toEqual(getOverview());
  });
});
