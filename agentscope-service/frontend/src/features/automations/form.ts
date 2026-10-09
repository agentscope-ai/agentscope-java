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

import type {
  Automation,
  AutomationExecution,
  AutomationTrigger,
  AutomationWrite,
} from "@/api/automations";

export const defaultExecution = (): AutomationExecution => ({
  runbook: "",
  assigneeType: "agent",
  assigneeRef: "",
  outputMode: "create_issue",
  completionPolicy: "review",
  concurrencyPolicy: "skip",
  queueTimeoutSeconds: 3600,
  runTimeoutSeconds: 3600,
  contextRefs: [],
  subscribers: [],
});
export const defaultTrigger = (
  type: "cron" | "webhook" = "cron",
): AutomationTrigger => ({
  id: crypto.randomUUID(),
  type,
  enabled: true,
  ...(type === "cron"
    ? {
        schedule: "0 9 * * 1-5",
        timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC",
      }
    : { events: [] }),
});
export function executionForForm(rule?: Automation): AutomationExecution {
  if (rule?.execution) return structuredClone(rule.execution);
  const action = (
    rule?.actionConfig && typeof rule.actionConfig === "object"
      ? rule.actionConfig
      : {}
  ) as Record<string, unknown>;
  return {
    ...defaultExecution(),
    runbook: typeof action.description === "string" ? action.description : "",
    assigneeType: action.assigneeType === "team" ? "team" : "agent",
    assigneeRef:
      typeof action.assigneeRef === "string" ? action.assigneeRef : "",
  };
}
export function buildAutomationWrite(
  tenant: string,
  namespace: string,
  name: string,
  description: string,
  enabled: boolean,
  execution: AutomationExecution,
  triggers: AutomationTrigger[],
  version?: number,
): AutomationWrite {
  if (!name.trim()) throw new Error("Enter an automation name.");
  if (!execution.runbook.trim())
    throw new Error("Describe the work in the runbook.");
  if (!execution.assigneeRef) throw new Error("Select an Agent or Team.");
  return {
    tenant,
    namespace,
    name: name.trim(),
    description: description.trim(),
    enabled,
    execution: {
      ...execution,
      runbook: execution.runbook.trim(),
      completionPolicy:
        execution.outputMode === "run_only"
          ? "automatic"
          : execution.completionPolicy,
    },
    triggers: triggers.map(
      ({ nextRunAt: _next, lastFiredAt: _last, ...trigger }) => trigger,
    ),
    ...(version === undefined ? {} : { expectedVersion: version }),
  };
}
export function automationError(error: unknown): string {
  const message =
    error instanceof Error
      ? error.message
      : "The request could not be completed.";
  try {
    const body = JSON.parse(message) as { error?: string };
    return body.error || message;
  } catch {
    return message;
  }
}
export const terminalRun = (status: string) =>
  ["completed", "failed", "skipped", "cancelled", "duplicate"].includes(status);
export function runLabel(status: string, waitReason?: string) {
  if (status === "waiting" && waitReason === "review") return "Awaiting review";
  return (
    (
      {
        queued: "Queued",
        dispatching: "Dispatching",
        running: "Running",
        waiting: "Waiting",
        completed: "Completed",
        failed: "Failed",
        skipped: "Skipped",
        cancelled: "Cancelled",
      } as Record<string, string>
    )[status] || status
  );
}
