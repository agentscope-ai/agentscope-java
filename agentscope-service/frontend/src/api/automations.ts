/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { api, apiFetch } from "@/lib/apiClient";

export interface AutomationExecution {
  runbook: string;
  assigneeType: "agent" | "team";
  assigneeRef: string;
  outputMode: "create_issue" | "run_only";
  completionPolicy: "automatic" | "review";
  contextRefs?: unknown[];
  concurrencyPolicy: "skip" | "queue";
  queueTimeoutSeconds: number;
  runTimeoutSeconds: number;
  subscribers?: string[];
}
export interface AutomationTrigger {
  id: string;
  type: "cron" | "webhook" | "channel";
  enabled: boolean;
  schedule?: string;
  timezone?: string;
  events?: string[];
  nextRunAt?: string;
  lastFiredAt?: string;
}
export interface Automation {
  id: string;
  tenant: string;
  namespace: string;
  name: string;
  description?: string;
  enabled: boolean;
  triggerType: string;
  triggerConfig?: unknown;
  actionType: string;
  actionConfig: unknown;
  execution?: AutomationExecution;
  triggers?: AutomationTrigger[];
  webhookConfigured?: boolean;
  nextRunAt?: string;
  lastRunAt?: string;
  version: number;
  createdAt: string;
  updatedAt: string;
  archivedAt?: string;
  createdBy?: { type: string; ref: string };
}
export interface AutomationRun {
  id: string;
  automationId: string;
  source?: string;
  triggerId?: string;
  triggerRef?: string;
  triggerType: string;
  status: string;
  waitReason?: string;
  issueId?: string;
  agentTaskId?: string;
  orchestrationRunId?: string;
  input?: unknown;
  output?: unknown;
  errorCode?: string;
  errorMessage?: string;
  snapshot?: Automation;
  version: number;
  createdAt: string;
  updatedAt?: string;
  scheduledAt?: string;
  startedAt?: string;
  executionCompletedAt?: string;
  completedAt?: string;
  rerunOf?: string;
}
export interface AutomationDelivery {
  id: string;
  automationId: string;
  triggerId: string;
  idempotencyKey: string;
  input?: unknown;
  event: string;
  status: string;
  reason?: string;
  runId?: string;
  replayedFrom?: string;
  createdAt: string;
}
export interface AutomationWrite {
  tenant: string;
  namespace: string;
  name: string;
  description: string;
  enabled: boolean;
  execution: AutomationExecution;
  triggers: AutomationTrigger[];
  expectedVersion?: number;
}
export interface AutomationSaved {
  automation: Automation;
  webhookSecret?: string;
}
const base = "/api/v1/automations";
const rulePath = (id: string) => `${base}/${encodeURIComponent(id)}`;
const runPath = (id: string, run: string) =>
  `${rulePath(id)}/runs/${encodeURIComponent(run)}`;
export const listAutomations = (
  tenant: string,
  namespace: string,
  offset = 0,
) =>
  api.get<{ items: Automation[] }>(
    `${base}?${new URLSearchParams({ tenant, namespace, limit: "100", offset: String(offset) })}`,
  );
export const getAutomation = (id: string) =>
  api.get<{ automation: Automation }>(rulePath(id));
export const createAutomation = (body: AutomationWrite) =>
  api.post<AutomationSaved>(base, body);
export const updateAutomation = (
  id: string,
  body: Partial<AutomationWrite> & { expectedVersion: number },
) => api.patch<AutomationSaved>(rulePath(id), body);
export const archiveAutomation = (id: string, version: number) =>
  api.delete(`${rulePath(id)}?expectedVersion=${version}`);
export const triggerAutomation = (
  id: string,
  key: string = crypto.randomUUID(),
  input?: unknown,
  test = false,
) =>
  apiFetch<{ run: AutomationRun }>(
    `${rulePath(id)}/trigger${test ? "?test=true" : ""}`,
    {
      method: "POST",
      headers: { "Idempotency-Key": key },
      body: input === undefined ? undefined : JSON.stringify(input),
    },
  );
export const listAutomationRuns = (id: string, offset = 0) =>
  api.get<{ items: AutomationRun[] }>(
    `${rulePath(id)}/runs?limit=25&offset=${offset}`,
  );
export interface AutomationRunDetail {
  run: AutomationRun;
  issue?: { id: string; title: string; status: string; visibility: string };
  tasks?: {
    id: string;
    status: string;
    sessionId?: string;
    agentId: string;
    result?: unknown;
    errorMessage?: string;
  }[];
  artifacts?: { id: string; filename: string; contentType: string }[];
}
export const getAutomationRun = (id: string, run: string) =>
  api.get<AutomationRunDetail>(runPath(id, run));
export const cancelAutomationRun = (id: string, run: string) =>
  api.post<{ run: AutomationRun }>(`${runPath(id, run)}/cancel`);
export const rerunAutomationRun = (id: string, run: string, key: string) =>
  apiFetch<{ run: AutomationRun }>(`${runPath(id, run)}/rerun`, {
    method: "POST",
    headers: { "Idempotency-Key": key },
  });
export const previewAutomationSchedule = (schedule: string, timezone: string) =>
  api.post<{ nextRuns: string[] }>(`${base}/schedule-preview`, {
    schedule,
    timezone,
  });
export const rotateAutomationSecret = (id: string, version: number) =>
  api.post<AutomationSaved>(`${rulePath(id)}/rotate-secret`, {
    expectedVersion: version,
  });
export const listAutomationDeliveries = (id: string, offset = 0) =>
  api.get<{ items: AutomationDelivery[] }>(
    `${rulePath(id)}/deliveries?limit=25&offset=${offset}`,
  );
export const replayAutomationDelivery = (
  id: string,
  delivery: string,
  key: string,
) =>
  apiFetch<{ delivery: AutomationDelivery }>(
    `${rulePath(id)}/deliveries/${encodeURIComponent(delivery)}/replay`,
    { method: "POST", headers: { "Idempotency-Key": key } },
  );
