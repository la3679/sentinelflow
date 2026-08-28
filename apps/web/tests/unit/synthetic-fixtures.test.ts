import { describe, expect, it } from "vitest";

import { getModelPolicy, getOverview, getReports, getSystemHealth } from "@/mocks/mockApi";
import { ALERTS } from "@/mocks/fixtures";
import { ALERT_PRIORITIES, ALERT_STATUSES } from "@/domain/types";

describe("synthetic fixtures", () => {
  it("uses only fictional references, in the shapes the contract publishes", () => {
    expect(ALERTS.length).toBeGreaterThan(0);
    for (const alert of ALERTS) {
      expect(alert.alertReference).toMatch(/^ALT-\d{4}$/);
      // The identifier is a UUID and the reference is the handle (ADR-0007).
      // A fixture that used one readable string for both would hide the
      // two-field design every screen has to respect.
      expect(alert.alertId).toMatch(/^[0-9a-f-]{36}$/);
      expect(ALERT_STATUSES).toContain(alert.status);
      expect(ALERT_PRIORITIES).toContain(alert.priority);
    }
  });

  it("offers no transition on a fixture alert", () => {
    // The overview lists alerts and offers no move on any of them. A fixture
    // that guessed at legalTargets would be the second copy of the state
    // machine this migration deleted.
    for (const alert of ALERTS) {
      expect(alert.legalTargets).toEqual([]);
    }
  });

  it("contains nothing resembling a card number or national identifier", () => {
    const serialized = JSON.stringify({
      overview: getOverview(),
      reports: getReports(),
      model: getModelPolicy(),
      health: getSystemHealth(),
    });
    // 13-19 consecutive digits would look like a PAN; NNN-NN-NNNN like an SSN.
    expect(serialized).not.toMatch(/\d{13,19}/);
    expect(serialized).not.toMatch(/\b\d{3}-\d{2}-\d{4}\b/);
  });

  it("is deterministic across repeated reads", () => {
    expect(getOverview()).toEqual(getOverview());
    expect(getReports()).toEqual(getReports());
    expect(getSystemHealth()).toEqual(getSystemHealth());
  });
});
