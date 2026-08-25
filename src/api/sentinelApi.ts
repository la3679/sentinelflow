import { createApi } from "@reduxjs/toolkit/query/react";

import { API_BASE_URL } from "@/api/config";
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
import { mockBaseQuery, type ApiRequest } from "@/mocks/mockBaseQuery";

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

const request = (input: ApiRequest): ApiRequest => input;

export const sentinelApi = createApi({
  reducerPath: "sentinelApi",
  baseQuery: mockBaseQuery,
  tagTypes: ["Alert", "AlertList", "Overview"],
  endpoints: (builder) => ({
    getOverview: builder.query<OverviewSnapshot, void>({
      query: () => request({ url: `${API_BASE_URL}/overview` }),
      providesTags: ["Overview"],
    }),
    listTransactions: builder.query<Paginated<Transaction>, TransactionListArgs>({
      query: (args) => request({ url: `${API_BASE_URL}/transactions`, params: { ...args } }),
    }),
    getTransaction: builder.query<TransactionDetail, string>({
      query: (transactionId) =>
        request({ url: `${API_BASE_URL}/transactions/${encodeURIComponent(transactionId)}` }),
    }),
    listAlerts: builder.query<Paginated<AlertSummary>, AlertListArgs>({
      query: (args) => request({ url: `${API_BASE_URL}/alerts`, params: { ...args } }),
      providesTags: ["AlertList"],
    }),
    getAlert: builder.query<AlertDetail, string>({
      query: (alertId) => request({ url: `${API_BASE_URL}/alerts/${encodeURIComponent(alertId)}` }),
      providesTags: (_result, _error, alertId) => [{ type: "Alert" as const, id: alertId }],
    }),
    assignAlert: builder.mutation<
      AlertDetail,
      { alertId: string; assignee: string; actor: string }
    >({
      query: ({ alertId, assignee, actor }) =>
        request({
          url: `${API_BASE_URL}/alerts/${encodeURIComponent(alertId)}/assignee`,
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
        request({
          url: `${API_BASE_URL}/alerts/${encodeURIComponent(alertId)}/status`,
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
        request({
          url: `${API_BASE_URL}/alerts/${encodeURIComponent(alertId)}/notes`,
          method: "POST",
          body: { body, actor },
        }),
      invalidatesTags: (_r, _e, { alertId }) => [{ type: "Alert" as const, id: alertId }],
    }),
    getReports: builder.query<ReportsSnapshot, void>({
      query: () => request({ url: `${API_BASE_URL}/reports` }),
    }),
    getModelPolicy: builder.query<ModelPolicySnapshot, void>({
      query: () => request({ url: `${API_BASE_URL}/model-policy` }),
    }),
    getSystemHealth: builder.query<SystemHealthSnapshot, void>({
      query: () => request({ url: `${API_BASE_URL}/health` }),
    }),
  }),
});

export const {
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
