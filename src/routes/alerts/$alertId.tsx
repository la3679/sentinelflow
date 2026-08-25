import { zodResolver } from "@hookform/resolvers/zod";
import { createFileRoute, Link } from "@tanstack/react-router";
import { useForm } from "react-hook-form";
import { z } from "zod";

import { AppShell, ReadOnlyNotice } from "@/components/app-shell";
import { AlertStatusChip, PriorityChip, RiskBandChip } from "@/components/chips";
import { QueryState } from "@/components/data-state";
import { FieldRow, PageHeader, Panel, StatTile } from "@/components/panel";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Textarea } from "@/components/ui/textarea";
import {
  ALERT_STATUS_LABELS,
  formatAge,
  formatDateTime,
  formatMoney,
  ROLE_LABELS,
} from "@/domain/labels";
import { ALLOWED_TRANSITIONS, canMutate, type AlertDetail } from "@/domain/types";
import {
  useAddAlertNoteMutation,
  useAssignAlertMutation,
  useGetAlertQuery,
  useTransitionAlertMutation,
} from "@/api/sentinelApi";
import { useSession } from "@/store";

export const Route = createFileRoute("/alerts/$alertId")({
  head: ({ params }) => ({
    meta: [
      { title: `Alert ${params.alertId} — SentinelFlow investigation` },
      {
        name: "description",
        content: `Investigation view for synthetic alert ${params.alertId}: risk breakdown, reason codes, timeline, notes and audit history.`,
      },
      { property: "og:title", content: `Alert ${params.alertId} — SentinelFlow investigation` },
      {
        property: "og:description",
        content: "Risk breakdown, reason codes, timeline and audit history for a synthetic alert.",
      },
    ],
  }),
  component: AlertDetailPage,
});

const ASSIGNEES = ["analyst.a1", "analyst.b2", "analyst.c3", "admin.z9"] as const;

const noteSchema = z.object({
  body: z
    .string()
    .min(5, "A note must be at least 5 characters.")
    .max(500, "Notes are limited to 500 characters."),
});

type NoteFormValues = z.infer<typeof noteSchema>;

function AlertDetailPage() {
  const { alertId } = Route.useParams();
  const query = useGetAlertQuery(alertId);

  return (
    <AppShell>
      <PageHeader
        title={`Alert ${alertId}`}
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
        loadingLabel={`Loading alert ${alertId}`}
        loadingRows={8}
      >
        {(alert) => <AlertWorkspace alert={alert} />}
      </QueryState>
    </AppShell>
  );
}

function AlertWorkspace({ alert }: { alert: AlertDetail }) {
  const session = useSession();
  const mutable = canMutate(session.role);
  const [assignAlert, assignState] = useAssignAlertMutation();
  const [transitionAlert, transitionState] = useTransitionAlertMutation();
  const [addNote, noteState] = useAddAlertNoteMutation();

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<NoteFormValues>({ resolver: zodResolver(noteSchema), defaultValues: { body: "" } });

  const transitions = ALLOWED_TRANSITIONS[alert.status];
  const assessment = alert.transaction.assessment;

  const onSubmitNote = async (values: NoteFormValues) => {
    await addNote({
      alertId: alert.alertId,
      body: values.body,
      actor: session.operatorId,
    }).unwrap();
    reset({ body: "" });
  };

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center gap-2">
        <PriorityChip priority={alert.priority} />
        <AlertStatusChip status={alert.status} />
        <RiskBandChip band={alert.riskBand} />
        <span className="text-xs text-muted-foreground">
          Opened {formatDateTime(alert.createdAt)} · age {formatAge(alert.ageMinutes)}
        </span>
      </div>

      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatTile
          label="Rule score"
          value={String(assessment.ruleScore)}
          hint="Deterministic rules"
        />
        <StatTile label="Model score" value={String(assessment.modelScore)} hint="ML inference" />
        <StatTile
          label="Final score"
          value={String(assessment.finalScore)}
          hint="Blended policy score"
          emphasis={assessment.finalScore >= 70}
        />
        <StatTile
          label="Scoring latency"
          value={`${assessment.scoringLatencyMs} ms`}
          hint="End-to-end assessment"
        />
      </div>

      <div className="grid gap-4 xl:grid-cols-3">
        <Panel title="Transaction summary" bodyClassName="p-0">
          <dl>
            <FieldRow label="Transaction">
              <Link
                to="/transactions/$transactionId"
                params={{ transactionId: alert.transactionId }}
                className="tabular underline underline-offset-4"
              >
                {alert.transactionId}
              </Link>
            </FieldRow>
            <FieldRow label="Account">
              <span className="tabular">{alert.accountId}</span>
            </FieldRow>
            <FieldRow label="Merchant">
              <span className="tabular">{alert.transaction.merchantId}</span>
            </FieldRow>
            <FieldRow label="Category">{alert.transaction.merchantCategory}</FieldRow>
            <FieldRow label="Channel">{alert.transaction.channel.replaceAll("_", " ")}</FieldRow>
            <FieldRow label="Amount">
              <span className="tabular">
                {formatMoney(alert.transaction.money.amount, alert.transaction.money.currency)}
              </span>
            </FieldRow>
            <FieldRow label="Country">{alert.transaction.countryCode}</FieldRow>
            <FieldRow label="Occurred at">
              <span className="tabular text-xs">
                {formatDateTime(alert.transaction.occurredAt)}
              </span>
            </FieldRow>
          </dl>
        </Panel>

        <Panel
          title="Reason codes"
          description="Contribution to the final score, in score points."
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
              {assessment.reasonCodes.map((reason) => (
                <TableRow key={reason.code}>
                  <TableCell className="tabular">{reason.code}</TableCell>
                  <TableCell className="text-xs">{reason.label}</TableCell>
                  <TableCell className="text-xs">{reason.source}</TableCell>
                  <TableCell className="tabular text-right">{reason.contribution}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Panel>

        <Panel title="Assessment metadata" bodyClassName="p-0">
          <dl>
            <FieldRow label="Assessment ID">
              <span className="tabular">{assessment.assessmentId}</span>
            </FieldRow>
            <FieldRow label="Model version">
              <span className="tabular">{assessment.modelVersion}</span>
            </FieldRow>
            <FieldRow label="Feature version">
              <span className="tabular">{assessment.featureVersion}</span>
            </FieldRow>
            <FieldRow label="Policy version">
              <span className="tabular">{assessment.policyVersion}</span>
            </FieldRow>
            <FieldRow label="Correlation ID">
              <span className="tabular text-xs">{alert.transaction.correlationId}</span>
            </FieldRow>
            <FieldRow label="Assessed at">
              <span className="tabular text-xs">{formatDateTime(assessment.assessedAt)}</span>
            </FieldRow>
          </dl>
        </Panel>
      </div>

      <div className="grid gap-4 xl:grid-cols-2">
        <Panel
          title="Case actions"
          description={`Acting as ${session.operatorId} — ${ROLE_LABELS[session.role]}.`}
          bodyClassName="space-y-4 p-4"
        >
          <ReadOnlyNotice role={session.role} />

          <div className="space-y-2">
            <Label htmlFor="assignee">Assignee</Label>
            <Select
              {...(alert.assignee ? { value: alert.assignee } : {})}
              disabled={!mutable || assignState.isLoading}
              onValueChange={(value) => {
                void assignAlert({
                  alertId: alert.alertId,
                  assignee: value,
                  actor: session.operatorId,
                });
              }}
            >
              <SelectTrigger id="assignee" className="w-full max-w-xs">
                <SelectValue placeholder="Unassigned" />
              </SelectTrigger>
              <SelectContent>
                {ASSIGNEES.map((person) => (
                  <SelectItem key={person} value={person}>
                    {person}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>

          <div className="space-y-2">
            <p className="text-xs font-medium tracking-wide text-muted-foreground uppercase">
              Valid state transitions
            </p>
            {transitions.length === 0 ? (
              <p className="text-sm text-muted-foreground">
                {ALERT_STATUS_LABELS[alert.status]} is a terminal state. No further transitions are
                available.
              </p>
            ) : (
              <div className="flex flex-wrap gap-2">
                {transitions.map((next) => (
                  <Button
                    key={next}
                    type="button"
                    variant="outline"
                    disabled={!mutable || transitionState.isLoading}
                    onClick={() => {
                      void transitionAlert({
                        alertId: alert.alertId,
                        status: next,
                        actor: session.operatorId,
                      });
                    }}
                  >
                    Move to {ALERT_STATUS_LABELS[next]}
                  </Button>
                ))}
              </div>
            )}
          </div>

          <form className="space-y-2" onSubmit={handleSubmit(onSubmitNote)} noValidate>
            <Label htmlFor="note-body">Investigation note</Label>
            <Textarea
              id="note-body"
              rows={3}
              disabled={!mutable}
              aria-invalid={errors.body ? true : undefined}
              aria-describedby={errors.body ? "note-error" : undefined}
              placeholder="Record what you checked and what you concluded."
              {...register("body")}
            />
            {errors.body ? (
              <p id="note-error" role="alert" className="text-xs text-destructive">
                {errors.body.message}
              </p>
            ) : null}
            <Button type="submit" disabled={!mutable || noteState.isLoading}>
              Add note
            </Button>
          </form>
        </Panel>

        <Panel title="Investigation timeline" bodyClassName="p-4">
          <ol className="space-y-3">
            {alert.timeline.map((event) => (
              <li key={event.eventId} className="border-l-2 border-border-strong pl-3">
                <p className="text-sm">{event.message}</p>
                <p className="tabular mt-1 text-xs text-muted-foreground">
                  {formatDateTime(event.occurredAt)} · {event.actor} · {event.kind}
                </p>
              </li>
            ))}
          </ol>
          {alert.notes.length > 0 ? (
            <div className="mt-6">
              <h3 className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">
                Notes
              </h3>
              <ul className="mt-2 space-y-2">
                {alert.notes.map((note) => (
                  <li key={note.noteId} className="rounded-md border border-border bg-surface p-3">
                    <p className="text-sm">{note.body}</p>
                    <p className="tabular mt-1 text-xs text-muted-foreground">
                      {note.author} · {formatDateTime(note.createdAt)}
                    </p>
                  </li>
                ))}
              </ul>
            </div>
          ) : null}
        </Panel>
      </div>

      <Panel title="Audit history" bodyClassName="overflow-x-auto">
        <Table>
          <caption className="sr-only">Audit history for this synthetic alert</caption>
          <TableHeader>
            <TableRow>
              <TableHead scope="col">Occurred at</TableHead>
              <TableHead scope="col">Action</TableHead>
              <TableHead scope="col">Actor</TableHead>
              <TableHead scope="col">Role</TableHead>
              <TableHead scope="col">Detail</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {alert.audit.map((entry) => (
              <TableRow key={entry.entryId}>
                <TableCell className="tabular text-xs">
                  {formatDateTime(entry.occurredAt)}
                </TableCell>
                <TableCell className="text-xs">{entry.action}</TableCell>
                <TableCell className="tabular">{entry.actor}</TableCell>
                <TableCell className="text-xs">{ROLE_LABELS[entry.actorRole]}</TableCell>
                <TableCell className="text-xs">{entry.detail}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Panel>
    </div>
  );
}
