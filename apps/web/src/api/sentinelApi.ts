import { createApi } from "@reduxjs/toolkit/query/react";

import { sentinelBaseQuery, type SentinelRequest } from "@/api/transport";
import type {
  Alert,
  AlertAction,
  AlertPriority,
  AlertStatus,
  ModelPolicySnapshot,
  OverviewSnapshot,
  Page,
  ReportsSnapshot,
  RiskAssessment,
  RiskBand,
  SystemHealthSnapshot,
  Transaction,
} from "@/domain/types";

/** `POST /auth/login`. The one request made without a token, and the one that returns one. */
export interface LoginArgs {
  username: string;
  password: string;
}

/** `TokenResponse` in the contract. `roles` is validated before it reaches the store. */
export interface TokenResponse {
  token: string;
  tokenType: string;
  expiresAt: string;
  roles: string[];
}

/**
 * The queue's filters, which are exactly the ones `GET /alerts` accepts.
 *
 * There is no free-text search and no risk-band filter, because the API has
 * neither. Offering one would be a control that quietly did nothing — Phase 6's
 * gate is that there are none of those.
 *
 * `page` is zero-based here because it is zero-based in the contract. Carrying
 * a one-based page in the client and subtracting on the way out is the kind of
 * translation that survives until somebody forgets it.
 */
export interface AlertListArgs {
  page: number;
  size: number;
  status?: AlertStatus | undefined;
  priority?: AlertPriority | undefined;
}

/** The filters `GET /transactions` accepts, and no others. */
export interface TransactionListArgs {
  page: number;
  size: number;
  accountReference?: string | undefined;
  riskBand?: RiskBand | undefined;
}

/** A request the server answers today. */
const real = (input: SentinelRequest): SentinelRequest => input;

/**
 * A request no server answers yet, resolved from fixtures.
 *
 * The marker is per endpoint so the console can be half-migrated honestly. The
 * audit lists what each of these needs before it can lose the marker.
 */
const fixture = (input: SentinelRequest): SentinelRequest => ({ ...input, transport: "mock" });

/** Query parameters, with the ones nobody chose left off rather than sent empty. */
function params(
  values: Record<string, string | number | undefined>,
): Record<string, string | number> {
  return Object.fromEntries(
    Object.entries(values).filter((entry): entry is [string, string | number] => {
      const [, value] = entry;
      return value !== undefined && value !== "";
    }),
  );
}

export const sentinelApi = createApi({
  reducerPath: "sentinelApi",
  baseQuery: sentinelBaseQuery,
  tagTypes: ["Alert", "AlertList", "AlertHistory", "Overview"],
  endpoints: (builder) => ({
    login: builder.mutation<TokenResponse, LoginArgs>({
      query: (credentials) => real({ url: "/auth/login", method: "POST", body: credentials }),
    }),
    getOverview: builder.query<OverviewSnapshot, void>({
      query: () => fixture({ url: `/overview` }),
      providesTags: ["Overview"],
    }),
    listTransactions: builder.query<Page<Transaction>, TransactionListArgs>({
      query: (args) => real({ url: `/transactions`, params: params({ ...args }) }),
    }),
    getTransaction: builder.query<Transaction, string>({
      query: (transactionId) => real({ url: `/transactions/${encodeURIComponent(transactionId)}` }),
    }),
    /**
     * `404` is a normal outcome here rather than a failure: ingestion is
     * asynchronous, so a transaction can legitimately have no assessment yet.
     */
    getTransactionAssessment: builder.query<RiskAssessment, string>({
      query: (transactionId) =>
        real({ url: `/transactions/${encodeURIComponent(transactionId)}/assessment` }),
    }),
    listAlerts: builder.query<Page<Alert>, AlertListArgs>({
      query: (args) => real({ url: `/alerts`, params: params({ ...args }) }),
      providesTags: ["AlertList"],
    }),
    getAlert: builder.query<Alert, string>({
      query: (alertId) => real({ url: `/alerts/${encodeURIComponent(alertId)}` }),
      providesTags: (_result, _error, alertId) => [{ type: "Alert" as const, id: alertId }],
    }),
    getAlertHistory: builder.query<Page<AlertAction>, { alertId: string; size?: number }>({
      query: ({ alertId, size = 50 }) =>
        real({ url: `/alerts/${encodeURIComponent(alertId)}/history`, params: { size } }),
      providesTags: (_result, _error, { alertId }) => [
        { type: "AlertHistory" as const, id: alertId },
      ],
    }),
    /**
     * `PUT`, and one operation for both directions: a null `assigneeId` releases
     * the alert back to the queue rather than assigning it to nobody.
     */
    assignAlert: builder.mutation<
      Alert,
      { alertId: string; assigneeId: string | null; expectedVersion: number; note?: string }
    >({
      query: ({ alertId, assigneeId, expectedVersion, note }) =>
        real({
          url: `/alerts/${encodeURIComponent(alertId)}/assignment`,
          method: "PUT",
          body: { assigneeId, expectedVersion, ...(note ? { note } : {}) },
        }),
      invalidatesTags: (_r, _e, { alertId }) => [
        { type: "Alert" as const, id: alertId },
        { type: "AlertHistory" as const, id: alertId },
        "AlertList",
      ],
    }),
    transitionAlert: builder.mutation<
      Alert,
      { alertId: string; targetStatus: AlertStatus; expectedVersion: number; note?: string }
    >({
      query: ({ alertId, targetStatus, expectedVersion, note }) =>
        real({
          url: `/alerts/${encodeURIComponent(alertId)}/transition`,
          method: "POST",
          body: { targetStatus, expectedVersion, ...(note ? { note } : {}) },
        }),
      invalidatesTags: (_r, _e, { alertId }) => [
        { type: "Alert" as const, id: alertId },
        { type: "AlertHistory" as const, id: alertId },
        "AlertList",
        "Overview",
      ],
    }),
    /**
     * A note carries no `expectedVersion`, because it appends rather than
     * replaces: two analysts writing one at the same time both succeed, the
     * alert row is not touched, and its version does not move. The response is
     * the history entry that was written, not the alert.
     */
    addAlertNote: builder.mutation<AlertAction, { alertId: string; note: string }>({
      query: ({ alertId, note }) =>
        real({
          url: `/alerts/${encodeURIComponent(alertId)}/notes`,
          method: "POST",
          body: { note },
        }),
      invalidatesTags: (_r, _e, { alertId }) => [{ type: "AlertHistory" as const, id: alertId }],
    }),
    getReports: builder.query<ReportsSnapshot, void>({
      query: () => fixture({ url: `/reports` }),
    }),
    getModelPolicy: builder.query<ModelPolicySnapshot, void>({
      query: () => fixture({ url: `/model-policy` }),
    }),
    getSystemHealth: builder.query<SystemHealthSnapshot, void>({
      query: () => fixture({ url: `/health` }),
    }),
  }),
});

export const {
  useLoginMutation,
  useGetOverviewQuery,
  useListTransactionsQuery,
  useGetTransactionQuery,
  useGetTransactionAssessmentQuery,
  useListAlertsQuery,
  useGetAlertQuery,
  useGetAlertHistoryQuery,
  useAssignAlertMutation,
  useTransitionAlertMutation,
  useAddAlertNoteMutation,
  useGetReportsQuery,
  useGetModelPolicyQuery,
  useGetSystemHealthQuery,
} = sentinelApi;
