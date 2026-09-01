import { zodResolver } from "@hookform/resolvers/zod";
import { createFileRoute, Link } from "@tanstack/react-router";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { z } from "zod";

import { AppShell, ReadOnlyNotice } from "@/components/app-shell";
import { AlertStatusChip, PriorityChip, RiskBandChip } from "@/components/chips";
import { errorMessage, QueryState } from "@/components/data-state";
import { FieldRow, PageHeader, Panel, StatTile } from "@/components/panel";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import {
  ALERT_ACTION_LABELS,
  ALERT_STATUS_LABELS,
  formatAgeSince,
  formatDateTime,
  formatMoney,
  PROCESSING_STATUS_LABELS,
  ROLE_LABELS,
} from "@/domain/labels";
import type { Alert, AlertStatus, Operator } from "@/domain/types";
import {
  useAddAlertNoteMutation,
  useAssignAlertMutation,
  useGetAlertHistoryQuery,
  useGetAlertQuery,
  useGetTransactionAssessmentQuery,
  useGetTransactionQuery,
  useListOperatorsQuery,
  useTransitionAlertMutation,
} from "@/api/sentinelApi";
import type { SentinelError } from "@/api/transport";
import { principalRole, sessionCanMutate, useSession } from "@/store";

export const Route = createFileRoute("/alerts/$alertId")({
  head: ({ params }) => ({
    meta: [
      { title: `Alert ${params.alertId} — SentinelFlow investigation` },
      {
        name: "description",
        content: `Investigation view for a synthetic alert: risk breakdown, reason codes, audit history and the moves this reader may make.`,
      },
      { property: "og:title", content: `Alert — SentinelFlow investigation` },
      {
        property: "og:description",
        content: "Risk breakdown, reason codes and audit history for a synthetic alert.",
      },
    ],
  }),
  component: AlertDetailPage,
});

const noteSchema = z.object({
  note: z
    .string()
    .min(5, "A note must be at least 5 characters.")
    .max(2000, "Notes are limited to 2000 characters."),
});

type NoteFormValues = z.infer<typeof noteSchema>;

function AlertDetailPage() {
  const { alertId } = Route.useParams();
  const query = useGetAlertQuery(alertId);

  return (
    <AppShell>
      <PageHeader
        title={query.data ? `Alert ${query.data.alertReference}` : "Alert"}
        description="Investigation workspace for a single synthetic alert."
        actions={
          <Link to="/alerts" className="text-sm underline underline-offset-4">
            Back to queue
          </Link>
        }
      />
      <QueryState
        isLoading={query.isLoading}
        isError={query.isError}
        error={query.error}
        data={query.data}
        onRetry={() => void query.refetch()}
        loadingLabel="Loading alert"
        loadingRows={8}
      >
        {(alert) => <AlertWorkspace alert={alert} onReread={() => void query.refetch()} />}
      </QueryState>
    </AppShell>
  );
}

/**
 * What the console was trying to do when the API said the alert had moved.
 *
 * Kept so the conflict notice can name the move and offer it again at the
 * version the re-read produced, rather than saying "conflict" and leaving an
 * analyst to work out what happened to the note they were holding.
 */
type Intent =
  | { kind: "transition"; targetStatus: AlertStatus }
  | { kind: "release" }
  | { kind: "assign"; assigneeId: string; displayName: string };

/** A role as a person reads it. The picker says what the directory publishes. */
function roleLabel(role: string): string {
  return ROLE_LABELS[role as keyof typeof ROLE_LABELS] ?? role;
}

function isConflict(error: unknown): error is SentinelError {
  return typeof error === "object" && error !== null && (error as SentinelError).status === 409;
}

function AlertWorkspace({ alert, onReread }: { alert: Alert; onReread: () => void }) {
  const session = useSession();
  const role = principalRole(session.roles);
  const mutable = sessionCanMutate(session.roles);

  const transaction = useGetTransactionQuery(alert.transactionId);
  const assessment = useGetTransactionAssessmentQuery(alert.transactionId);
  const history = useGetAlertHistoryQuery({ alertId: alert.alertId });

  const [assignAlert, assignState] = useAssignAlertMutation();
  const operators = useListOperatorsQuery();

  /** Who the picker currently has selected. Empty until an operator chooses. */
  const [picked, setPicked] = useState<string>("");
  const [transitionAlert, transitionState] = useTransitionAlertMutation();
  const [addNote, noteState] = useAddAlertNoteMutation();

  /** The last refusal that was a conflict, with what was being attempted. */
  const [conflict, setConflict] = useState<{ intent: Intent; error: SentinelError } | null>(null);

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<NoteFormValues>({ resolver: zodResolver(noteSchema), defaultValues: { note: "" } });

  /**
   * Runs a mutation and, on a `409`, re-reads the alert before saying anything.
   *
   * A conflict means what this screen is showing is out of date, so the first
   * thing to do is stop showing it. The notice is rendered from the re-read
   * alert rather than from the problem body, which is what lets it offer the
   * move again instead of only reporting that it failed.
   */
  async function attempt(intent: Intent, run: () => Promise<unknown>): Promise<void> {
    setConflict(null);
    try {
      await run();
    } catch (error) {
      if (isConflict(error)) {
        onReread();
        setConflict({ intent, error });
      }
      // Anything else is rendered from the mutation's own error state below.
    }
  }

  const transition = (targetStatus: AlertStatus): void => {
    void attempt({ kind: "transition", targetStatus }, () =>
      transitionAlert({
        alertId: alert.alertId,
        targetStatus,
        expectedVersion: alert.version,
      }).unwrap(),
    );
  };

  const release = (): void => {
    void attempt({ kind: "release" }, () =>
      assignAlert({
        alertId: alert.alertId,
        assigneeId: null,
        expectedVersion: alert.version,
      }).unwrap(),
    );
  };

  const assign = (assigneeId: string, displayName: string): void => {
    void attempt({ kind: "assign", assigneeId, displayName }, () =>
      assignAlert({
        alertId: alert.alertId,
        assigneeId,
        expectedVersion: alert.version,
      }).unwrap(),
    );
  };

  const onSubmitNote = async (values: NoteFormValues): Promise<void> => {
    await addNote({ alertId: alert.alertId, note: values.note }).unwrap();
    reset({ note: "" });
  };

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center gap-2">
        <PriorityChip priority={alert.priority} />
        <AlertStatusChip status={alert.status} />
        <RiskBandChip band={alert.riskBand} />
        <span className="text-xs text-muted-foreground">
          Opened {formatDateTime(alert.createdAt)} · age {formatAgeSince(alert.createdAt)}
        </span>
      </div>

      <p className="text-sm">{alert.summary}</p>

      {conflict ? (
        <ConflictNotice
          alert={alert}
          intent={conflict.intent}
          error={conflict.error}
          onDismiss={() => setConflict(null)}
          onRetryTransition={transition}
          disabled={!mutable || transitionState.isLoading}
        />
      ) : null}

      <QueryState
        isLoading={assessment.isLoading}
        isError={assessment.isError}
        error={assessment.error}
        data={assessment.data}
        onRetry={() => void assessment.refetch()}
        loadingLabel="Loading the assessment behind this alert"
        loadingRows={2}
      >
        {(scored) => (
          <>
            <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
              <StatTile
                label="Rule score"
                value={String(scored.ruleScore)}
                hint={`Ruleset ${scored.rulesetVersion}`}
              />
              <StatTile
                label="Model score"
                value={scored.modelScore === null ? "—" : String(scored.modelScore)}
                hint={scored.degraded ? "Scoring unavailable — rules only" : "Model inference"}
              />
              <StatTile
                label="Final score"
                value={String(scored.finalScore)}
                hint={`Policy ${scored.policyVersion}`}
                emphasis={scored.finalScore >= 70}
              />
              <StatTile
                label="Scoring latency"
                value={
                  scored.scoringLatencyMs === undefined ? "—" : `${scored.scoringLatencyMs} ms`
                }
                hint="End-to-end assessment"
              />
            </div>

            <Panel
              title="Reason codes"
              description="What pushed the score, on the scale each source uses. A model contribution is comparable only within one model version."
              bodyClassName="overflow-x-auto"
            >
              <Table>
                <caption className="sr-only">Reason codes contributing to the final score</caption>
                <TableHeader>
                  <TableRow>
                    <TableHead scope="col">Code</TableHead>
                    <TableHead scope="col">Reason</TableHead>
                    <TableHead scope="col">Source</TableHead>
                    <TableHead scope="col" className="text-right">
                      Contribution
                    </TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {scored.reasonCodes.map((reason) => (
                    <TableRow key={reason.code}>
                      <TableCell className="tabular">{reason.code}</TableCell>
                      <TableCell className="text-xs">{reason.description}</TableCell>
                      <TableCell className="text-xs">{reason.source}</TableCell>
                      <TableCell className="tabular text-right">{reason.contribution}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </Panel>
          </>
        )}
      </QueryState>

      <div className="grid gap-4 xl:grid-cols-2">
        <Panel title="Transaction" bodyClassName="p-0">
          <QueryState
            isLoading={transaction.isLoading}
            isError={transaction.isError}
            error={transaction.error}
            data={transaction.data}
            onRetry={() => void transaction.refetch()}
            loadingLabel="Loading the transaction this alert is about"
            loadingRows={6}
          >
            {(txn) => (
              <dl>
                <FieldRow label="Reference">
                  <Link
                    to="/transactions/$transactionId"
                    params={{ transactionId: txn.transactionId }}
                    className="tabular underline underline-offset-4"
                  >
                    {txn.transactionReference}
                  </Link>
                </FieldRow>
                <FieldRow label="Account">
                  <span className="tabular">{txn.accountReference}</span>
                </FieldRow>
                <FieldRow label="Merchant">
                  <span className="tabular">{txn.merchantReference}</span>
                </FieldRow>
                {txn.merchantCategoryCode ? (
                  <FieldRow label="Category code">
                    <span className="tabular">{txn.merchantCategoryCode}</span>
                  </FieldRow>
                ) : null}
                {txn.channel ? (
                  <FieldRow label="Channel">{txn.channel.replaceAll("_", " ")}</FieldRow>
                ) : null}
                <FieldRow label="Amount">
                  <span className="tabular">
                    {formatMoney(txn.amount.value, txn.amount.currency)}
                  </span>
                </FieldRow>
                {txn.originCountry ? (
                  <FieldRow label="Origin country">{txn.originCountry}</FieldRow>
                ) : null}
                <FieldRow label="Occurred at">
                  <span className="tabular text-xs">{formatDateTime(txn.occurredAt)}</span>
                </FieldRow>
                <FieldRow label="Processing">
                  {PROCESSING_STATUS_LABELS[txn.processingStatus]}
                </FieldRow>
              </dl>
            )}
          </QueryState>
        </Panel>

        <Panel
          title="Case actions"
          description={`Acting as ${session.username ?? "unknown"}${role ? ` — ${ROLE_LABELS[role]}` : ""}.`}
          bodyClassName="space-y-4 p-4"
        >
          <ReadOnlyNotice role={role} />

          <div className="space-y-2">
            <p className="text-xs font-medium tracking-wide text-muted-foreground uppercase">
              Assignment
            </p>
            <p className="text-sm">
              {alert.assignee ? (
                <>
                  Held by <strong>{alert.assignee.displayName}</strong>{" "}
                  <span className="tabular text-xs text-muted-foreground">
                    {alert.assignee.username}
                  </span>
                </>
              ) : alert.assigneeId ? (
                /*
                  An identifier the API could not resolve. Not a state the schema
                  can currently reach, and rendered rather than hidden: showing
                  the raw identifier is the honest answer, and inventing a name
                  for it would be worse than admitting there is none.
                */
                <>
                  Held by an operator this API cannot name{" "}
                  <span className="tabular text-xs">{alert.assigneeId}</span>
                </>
              ) : (
                "Unassigned."
              )}
            </p>

            {mutable ? (
              <AssigneePicker
                operators={operators.data?.content ?? []}
                loading={operators.isLoading}
                failed={operators.isError}
                onRetry={() => void operators.refetch()}
                value={picked}
                onChange={setPicked}
                onAssign={assign}
                onAssignToMe={
                  session.operatorId
                    ? () => assign(session.operatorId!, session.displayName ?? "you")
                    : null
                }
                currentAssigneeId={alert.assigneeId}
                busy={assignState.isLoading}
              />
            ) : (
              <p className="text-xs text-muted-foreground">
                This role reads alerts and does not assign them.
              </p>
            )}

            <Button
              type="button"
              variant="outline"
              disabled={!mutable || alert.assigneeId === null || assignState.isLoading}
              onClick={release}
            >
              Release to the queue
            </Button>
            {assignState.isError && !isConflict(assignState.error) ? (
              <p role="alert" className="text-xs text-destructive">
                {errorMessage(assignState.error)}
              </p>
            ) : null}
          </div>

          <div className="space-y-2">
            <p className="text-xs font-medium tracking-wide text-muted-foreground uppercase">
              Moves you may make
            </p>
            {/*
              Rendered from the alert's own legalTargets and nothing else. The
              list is a property of the alert and this reader together, so an
              analyst is not offered the administrative close.
            */}
            {alert.legalTargets.length === 0 ? (
              <p className="text-sm text-muted-foreground">
                {mutable
                  ? `${ALERT_STATUS_LABELS[alert.status]} is a terminal state. There is nothing further to do here.`
                  : "This role reads alerts and does not move them."}
              </p>
            ) : (
              <div className="flex flex-wrap gap-2">
                {alert.legalTargets.map((next) => (
                  <Button
                    key={next}
                    type="button"
                    variant="outline"
                    disabled={!mutable || transitionState.isLoading}
                    onClick={() => transition(next)}
                  >
                    Move to {ALERT_STATUS_LABELS[next]}
                  </Button>
                ))}
              </div>
            )}
            {transitionState.isError && !isConflict(transitionState.error) ? (
              <p role="alert" className="text-xs text-destructive">
                {errorMessage(transitionState.error)}
              </p>
            ) : null}
          </div>

          <form className="space-y-2" onSubmit={handleSubmit(onSubmitNote)} noValidate>
            <Label htmlFor="note-body">Investigation note</Label>
            <Textarea
              id="note-body"
              rows={3}
              disabled={!mutable}
              aria-invalid={errors.note ? true : undefined}
              aria-describedby={errors.note ? "note-error" : undefined}
              placeholder="Record what you checked and what you concluded."
              {...register("note")}
            />
            {errors.note ? (
              <p id="note-error" role="alert" className="text-xs text-destructive">
                {errors.note.message}
              </p>
            ) : null}
            <Button type="submit" disabled={!mutable || noteState.isLoading}>
              Add note
            </Button>
            {noteState.isError ? (
              <p role="alert" className="text-xs text-destructive">
                {errorMessage(noteState.error)}
              </p>
            ) : null}
          </form>
        </Panel>
      </div>

      <Panel
        title="Audit history"
        description="Every action taken on this alert, newest first, with the capacity it was taken in."
        bodyClassName="overflow-x-auto"
      >
        <QueryState
          isLoading={history.isLoading}
          isError={history.isError}
          error={history.error}
          data={history.data}
          onRetry={() => void history.refetch()}
          loadingLabel="Loading the audit history"
          loadingRows={4}
          isEmpty={(page) => page.content.length === 0}
          emptyTitle="No history yet"
        >
          {(page) => (
            <Table>
              <caption className="sr-only">Audit history for this synthetic alert</caption>
              <TableHeader>
                <TableRow>
                  <TableHead scope="col">Occurred at</TableHead>
                  <TableHead scope="col">Action</TableHead>
                  <TableHead scope="col">Role</TableHead>
                  <TableHead scope="col">Change</TableHead>
                  <TableHead scope="col">Note</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {page.content.map((entry) => (
                  <TableRow key={entry.actionId}>
                    <TableCell className="tabular text-xs">
                      {formatDateTime(entry.occurredAt)}
                    </TableCell>
                    <TableCell className="text-xs">
                      {ALERT_ACTION_LABELS[entry.actionType]}
                    </TableCell>
                    <TableCell className="text-xs">{ROLE_LABELS[entry.actorRole]}</TableCell>
                    <TableCell className="text-xs">
                      {entry.newStatus
                        ? `${entry.previousStatus ? ALERT_STATUS_LABELS[entry.previousStatus] : "—"} → ${ALERT_STATUS_LABELS[entry.newStatus]}`
                        : "—"}
                    </TableCell>
                    <TableCell className="text-xs">{entry.note ?? "—"}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </QueryState>
      </Panel>
    </div>
  );
}

/**
 * What to say when the alert moved underneath the operator.
 *
 * Both `409`s this screen can provoke mean the same thing to a person — what
 * you were looking at is not what is there — so both are answered the same way:
 * re-read, say what it is now, and offer the move again if it is still one this
 * reader may make. `alert` here is already the re-read one.
 */
function ConflictNotice({
  alert,
  intent,
  error,
  onDismiss,
  onRetryTransition,
  disabled,
}: {
  alert: Alert;
  intent: Intent;
  error: SentinelError;
  onDismiss: () => void;
  onRetryTransition: (target: AlertStatus) => void;
  disabled: boolean;
}) {
  const stillLegal =
    intent.kind === "transition" && alert.legalTargets.includes(intent.targetStatus);

  return (
    <div
      role="alert"
      className="space-y-3 rounded-md border border-risk-medium bg-surface-raised p-4"
    >
      <div>
        <p className="font-semibold">This alert changed while you were reading it.</p>
        <p className="mt-1 text-sm text-muted-foreground">{errorMessage(error)}</p>
      </div>
      <p className="text-sm">
        It is now <strong>{ALERT_STATUS_LABELS[alert.status]}</strong>, at version{" "}
        <span className="tabular">{alert.version}</span>
        {alert.assignee
          ? `, and it is with ${alert.assignee.displayName}.`
          : alert.assigneeId
            ? ", and it is assigned."
            : ", and it is unassigned."}
      </p>
      <div className="flex flex-wrap items-center gap-2">
        {intent.kind === "transition" && stillLegal ? (
          <Button
            type="button"
            variant="outline"
            disabled={disabled}
            onClick={() => onRetryTransition(intent.targetStatus)}
          >
            Move to {ALERT_STATUS_LABELS[intent.targetStatus]} anyway
          </Button>
        ) : (
          <p className="text-sm text-muted-foreground">
            {intent.kind === "assign"
              ? `Assigning it to ${intent.displayName} was not applied. Read what it says above and choose again if you still want to.`
              : intent.kind === "transition"
                ? `Moving it to ${ALERT_STATUS_LABELS[intent.targetStatus]} is no longer one of the moves available from here.`
                : "Read the current state before releasing it."}
          </p>
        )}
        <Button type="button" variant="ghost" onClick={onDismiss}>
          Dismiss
        </Button>
      </div>
    </div>
  );
}

/**
 * The picker that turns "assign this alert" into a name instead of a UUID.
 *
 * <h2>Four states, because it is a data view</h2>
 *
 * `.claude/rules/frontend.md` requires loading, empty, error-with-retry and
 * bounded of every data view, and this is one — it reads `GET /operators`.
 * Bounded is the API's doing: it caps the page and refuses more, so this cannot
 * grow into an unbounded list however many operators exist.
 *
 * **Empty is a real state and not an impossible one.** A deployment whose
 * operators are all auditors, or all disabled, has nobody to assign to, and the
 * honest answer is to say so rather than to render a control with nothing
 * behind it.
 *
 * <h2>What it does not decide</h2>
 *
 * Nothing here authorizes anything. The directory lists who the API would
 * accept, and the API checks again on the way in — an assignment this picker
 * never offered is refused by the server, and `OperatorDirectoryIT` proves it.
 * Offering only what the server accepts is a courtesy to the operator, not a
 * security boundary.
 *
 * The operator already holding the alert is left out of the list, because
 * assigning somebody to work they already have is not a move: the API treats it
 * as a no-op and writes nothing, so offering it would be a control that appears
 * to do something and does not.
 */
function AssigneePicker({
  operators,
  loading,
  failed,
  onRetry,
  value,
  onChange,
  onAssign,
  onAssignToMe,
  currentAssigneeId,
  busy,
}: {
  operators: Operator[];
  loading: boolean;
  failed: boolean;
  onRetry: () => void;
  value: string;
  onChange: (value: string) => void;
  onAssign: (assigneeId: string, displayName: string) => void;
  onAssignToMe: (() => void) | null;
  currentAssigneeId: string | null;
  busy: boolean;
}) {
  if (loading) {
    return <p className="text-sm text-muted-foreground">Loading the operator directory…</p>;
  }

  if (failed) {
    return (
      <div className="space-y-2">
        <p role="alert" className="text-sm text-destructive">
          The operator directory could not be read, so there is nobody to choose from.
        </p>
        <Button type="button" variant="outline" onClick={onRetry}>
          Try again
        </Button>
      </div>
    );
  }

  const choices = operators.filter((operator) => operator.operatorId !== currentAssigneeId);

  if (choices.length === 0) {
    return (
      <p className="text-sm text-muted-foreground">
        There is nobody else to assign this to. The directory lists only active operators who hold a
        role that can work an alert.
      </p>
    );
  }

  const picked = choices.find((operator) => operator.operatorId === value);

  return (
    <div className="space-y-2">
      <Label htmlFor="assign-to">Assign to</Label>
      <div className="flex flex-wrap items-center gap-2">
        <Select value={value} onValueChange={onChange}>
          <SelectTrigger id="assign-to" className="w-full max-w-xs">
            <SelectValue placeholder="Choose an operator" />
          </SelectTrigger>
          <SelectContent>
            {choices.map((operator) => (
              <SelectItem key={operator.operatorId} value={operator.operatorId}>
                {operator.displayName} — {operator.roles.map(roleLabel).join(", ")}
              </SelectItem>
            ))}
          </SelectContent>
        </Select>
        <Button
          type="button"
          disabled={!picked || busy}
          onClick={() => picked && onAssign(picked.operatorId, picked.displayName)}
        >
          Assign
        </Button>
        {onAssignToMe ? (
          <Button type="button" variant="outline" disabled={busy} onClick={onAssignToMe}>
            Assign to me
          </Button>
        ) : null}
      </div>
    </div>
  );
}
