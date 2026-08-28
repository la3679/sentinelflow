import { createApi } from "@reduxjs/toolkit/query/react";

import { sentinelBaseQuery, type SentinelRequest } from "@/api/transport";
import type {
  AlertDetail,
  AlertStatus,
  AlertSummary,
  ModelPolicySnapshot,
  OverviewSnapshot,
  Paginated,
  ReportsSnapshot,
  RiskBand,
  SystemHealthSnapshot,
  Transaction,
  TransactionDetail,
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

export interface AlertListArgs {
  page: number;
  pageSize: number;
  status: AlertStatus | "ALL";
  riskBand: RiskBand | "ALL";
  search: string;
}

export interface TransactionListArgs {
  page: number;
  pageSize: number;
  status: Transaction["status"] | "ALL";
  riskBand: RiskBand | "ALL";
  search: string;
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

export const sentinelApi = createApi({
  reducerPath: "sentinelApi",
  baseQuery: sentinelBaseQuery,
  tagTypes: ["Alert", "AlertList", "Overview"],
  endpoints: (builder) => ({
    login: builder.mutation<TokenResponse, LoginArgs>({
      query: (credentials) => real({ url: "/auth/login", method: "POST", body: credentials }),
    }),
    getOverview: builder.query<OverviewSnapshot, void>({
      query: () => fixture({ url: `/overview` }),
      providesTags: ["Overview"],
    }),
    listTransactions: builder.query<Paginated<Transaction>, TransactionListArgs>({
      query: (args) => fixture({ url: `/transactions`, params: { ...args } }),
    }),
    getTransaction: builder.query<TransactionDetail, string>({
      query: (transactionId) =>
        fixture({ url: `/transactions/${encodeURIComponent(transactionId)}` }),
    }),
    listAlerts: builder.query<Paginated<AlertSummary>, AlertListArgs>({
      query: (args) => fixture({ url: `/alerts`, params: { ...args } }),
      providesTags: ["AlertList"],
    }),
    getAlert: builder.query<AlertDetail, string>({
      query: (alertId) => fixture({ url: `/alerts/${encodeURIComponent(alertId)}` }),
      providesTags: (_result, _error, alertId) => [{ type: "Alert" as const, id: alertId }],
    }),
    assignAlert: builder.mutation<
      AlertDetail,
      { alertId: string; assignee: string; actor: string }
    >({
      query: ({ alertId, assignee, actor }) =>
        fixture({
          url: `/alerts/${encodeURIComponent(alertId)}/assignee`,
          method: "PATCH",
          body: { assignee, actor },
        }),
      invalidatesTags: (_r, _e, { alertId }) => [
        { type: "Alert" as const, id: alertId },
        "AlertList",
      ],
    }),
    transitionAlert: builder.mutation<
      AlertDetail,
      { alertId: string; status: AlertStatus; actor: string }
    >({
      query: ({ alertId, status, actor }) =>
        fixture({
          url: `/alerts/${encodeURIComponent(alertId)}/status`,
          method: "PATCH",
          body: { status, actor },
        }),
      invalidatesTags: (_r, _e, { alertId }) => [
        { type: "Alert" as const, id: alertId },
        "AlertList",
        "Overview",
      ],
    }),
    addAlertNote: builder.mutation<AlertDetail, { alertId: string; body: string; actor: string }>({
      query: ({ alertId, body, actor }) =>
        fixture({
          url: `/alerts/${encodeURIComponent(alertId)}/notes`,
          method: "POST",
          body: { body, actor },
        }),
      invalidatesTags: (_r, _e, { alertId }) => [{ type: "Alert" as const, id: alertId }],
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
  useListAlertsQuery,
  useGetAlertQuery,
  useAssignAlertMutation,
  useTransitionAlertMutation,
  useAddAlertNoteMutation,
  useGetReportsQuery,
  useGetModelPolicyQuery,
  useGetSystemHealthQuery,
} = sentinelApi;
