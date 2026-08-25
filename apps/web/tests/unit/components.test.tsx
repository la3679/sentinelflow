import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import axe from "axe-core";
import { describe, expect, it, vi } from "vitest";

import { AlertStatusChip, PriorityChip, RiskBandChip } from "@/components/chips";
import { EmptyBlock, ErrorBlock, LoadingBlock } from "@/components/data-state";
import { ALERT_STATUS_LABELS, RISK_BAND_LABELS } from "@/domain/labels";
import { ALERT_STATUSES, RISK_BANDS } from "@/domain/types";

async function expectNoAxeViolations(container: HTMLElement): Promise<void> {
  const results = await axe.run(container, {
    // Landmark and page-level rules do not apply to an isolated fragment;
    // those are covered by the Playwright checks against real pages.
    runOnly: { type: "tag", values: ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"] },
  });
  const summary = results.violations.map((v) => `${v.id}: ${v.help}`);
  expect(summary).toEqual([]);
}

describe("status chips", () => {
  it("communicates every risk band with text, not colour alone", () => {
    for (const band of RISK_BANDS) {
      const { unmount } = render(<RiskBandChip band={band} />);
      // The label is real text, so the band is legible without relying on colour.
      expect(screen.getByText(RISK_BAND_LABELS[band])).toBeInTheDocument();
      unmount();
    }
  });

  it("communicates every alert status with a visible text label", () => {
    for (const status of ALERT_STATUSES) {
      const { unmount } = render(<AlertStatusChip status={status} />);
      expect(screen.getByText(ALERT_STATUS_LABELS[status])).toBeInTheDocument();
      unmount();
    }
  });

  it("renders priorities and passes an axe scan", async () => {
    const { container } = render(
      <div>
        <PriorityChip priority="P1" />
        <RiskBandChip band="CRITICAL" />
        <AlertStatusChip status="IN_REVIEW" />
      </div>,
    );
    expect(screen.getByText(/priority 1/i)).toBeInTheDocument();
    await expectNoAxeViolations(container);
  });
});

describe("data states", () => {
  it("announces loading to assistive technology", () => {
    render(<LoadingBlock label="Loading alerts" />);
    expect(screen.getByText(/loading alerts/i)).toBeInTheDocument();
  });

  it("renders an empty state with a title", () => {
    render(<EmptyBlock title="No alerts match these filters" hint="Try widening the range." />);
    expect(screen.getByText(/no alerts match these filters/i)).toBeInTheDocument();
    expect(screen.getByText(/try widening the range/i)).toBeInTheDocument();
  });

  it("offers a working retry on the error state", async () => {
    const retry = vi.fn();
    render(
      <ErrorBlock
        title="Could not load alerts"
        error={{ status: 500, message: "Mock failure." }}
        onRetry={retry}
      />,
    );

    const button = screen.getByRole("button", { name: /retry/i });
    await userEvent.click(button);
    expect(retry).toHaveBeenCalledTimes(1);
  });

  it("keeps the error state reachable by keyboard", async () => {
    const retry = vi.fn();
    const { container } = render(
      <ErrorBlock
        title="Could not load alerts"
        error={{ status: 500, message: "Mock failure." }}
        onRetry={retry}
      />,
    );

    await userEvent.tab();
    expect(screen.getByRole("button", { name: /retry/i })).toHaveFocus();
    await userEvent.keyboard("{Enter}");
    expect(retry).toHaveBeenCalledTimes(1);
    await expectNoAxeViolations(container);
  });
});
