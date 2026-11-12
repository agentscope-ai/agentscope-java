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

import type { AgentTask } from "@/api/collaboration";
import type { OrchestrationRun } from "@/api/orchestration";
import { resultSummary } from "@/features/build/agents/agentActivity";

export type TeamTask = AgentTask & {
  taskTitle?: string;
  parentTaskId?: string;
  delegatedFromTaskId?: string;
  waitReason?: string;
};
export type TeamActivity = {
  id: string;
  run?: OrchestrationRun;
  tasks: TeamTask[];
  state: string;
  title: string;
  summary: string;
  updatedAt: string;
  issueId: string;
};
export const terminalRun = (state: string) =>
  ["succeeded", "partial_succeeded", "failed", "cancelled"].includes(state);
export const attentionRun = (state: string) =>
  ["failed", "partial_succeeded", "waiting", "paused"].includes(state);
export function teamTone(
  state: string,
): "success" | "warning" | "danger" | "default" {
  if (["succeeded", "completed", "ready", "active"].includes(state))
    return "success";
  if (["failed", "unavailable"].includes(state)) return "danger";
  if (
    [
      "waiting",
      "paused",
      "partial_succeeded",
      "degraded",
      "running",
      "queued",
      "dispatched",
    ].includes(state)
  )
    return "warning";
  return "default";
}
export const stateLabel = (state: string) =>
  ({
    succeeded: "Succeeded",
    partial_succeeded: "Partially succeeded",
    unknown: "Status unavailable",
    planned: "Queued",
  })[state] || state.replace(/_/g, " ").replace(/^./, (s) => s.toUpperCase());
export function groupTeamActivity(
  tasks: TeamTask[],
  runs: OrchestrationRun[],
  titles: Record<string, string> = {},
): TeamActivity[] {
  const byRun = new Map(runs.map((run) => [run.id, run]));
  const groups = new Map<string, TeamTask[]>();
  for (const task of tasks) {
    const runId =
      task.orchestrationRunId &&
      task.orchestrationRunId !== "00000000-0000-0000-0000-000000000000"
        ? task.orchestrationRunId
        : "";
    // Never merge unrelated legacy records on an empty run ID or shared Issue.
    const id = runId || `task:${task.id}`;
    groups.set(id, [...(groups.get(id) || []), task]);
  }
  return [...groups]
    .map(([id, entries]) => {
      const run = byRun.get(id);
      const sorted = [...entries].sort(
        (a, b) =>
          a.createdAt.localeCompare(b.createdAt) || a.id.localeCompare(b.id),
      );
      const issueId = run?.rootIssueId || sorted[0].issueId;
      const updatedAt =
        [
          run?.completedAt,
          run?.startedAt,
          run?.createdAt,
          ...sorted.map((t) => t.completedAt || t.startedAt || t.createdAt),
        ]
          .filter((v): v is string => !!v)
          .sort()
          .slice(-1)[0] || "";
      return {
        id,
        run,
        tasks: sorted,
        issueId,
        state: run?.state || "unknown",
        updatedAt,
        title:
          titles[issueId] ||
          resultSummary(run?.input) ||
          `Collaboration ${id.slice(0, 8)}`,
        summary:
          run?.failureMessage ||
          run?.waitReason ||
          resultSummary(run?.output) ||
          (run
            ? "No final output recorded."
            : "Run status could not be loaded."),
      };
    })
    .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt));
}
export type TaskStep = {
  id: string;
  tasks: TeamTask[];
  latest: TeamTask;
  parentId?: string;
  relation?: "Delegated" | "Triggered" | "Child task";
};
export function buildTaskSteps(tasks: TeamTask[]): TaskStep[] {
  const byId = new Map(tasks.map((task) => [task.id, task]));
  const root = (task: TeamTask) => {
    let current = task;
    const seen = new Set([current.id]);
    while (
      current.retryOfTaskId &&
      byId.has(current.retryOfTaskId) &&
      !seen.has(current.retryOfTaskId)
    ) {
      current = byId.get(current.retryOfTaskId)!;
      seen.add(current.id);
    }
    // Stable grouping even for malformed cycles.
    return current.retryOfTaskId && seen.has(current.retryOfTaskId)
      ? [...seen].sort()[0]
      : current.id;
  };
  const groups = new Map<string, TeamTask[]>();
  for (const task of tasks) {
    const id = root(task);
    groups.set(id, [...(groups.get(id) || []), task]);
  }
  return [...groups].map(([id, entries]) => {
    const retryDepth = (task: TeamTask) => {
      const seen = new Set([task.id]);
      let current = task;
      let depth = 0;
      while (
        current.retryOfTaskId &&
        byId.has(current.retryOfTaskId) &&
        !seen.has(current.retryOfTaskId)
      ) {
        current = byId.get(current.retryOfTaskId)!;
        seen.add(current.id);
        depth++;
      }
      return depth;
    };
    const sorted = [...entries].sort(
      (a, b) =>
        retryDepth(a) - retryDepth(b) ||
        a.createdAt.localeCompare(b.createdAt) ||
        a.id.localeCompare(b.id),
    );
    const first = byId.get(id) || sorted[0];
    const parent = first.delegatedFromTaskId || first.parentTaskId;
    const parentId =
      parent && byId.has(parent) ? root(byId.get(parent)!) : undefined;
    return {
      id,
      tasks: sorted,
      latest: sorted[sorted.length - 1],
      parentId: parentId !== id ? parentId : undefined,
      relation: first.delegatedFromTaskId
        ? first.leaderTask
          ? ("Triggered" as const)
          : ("Delegated" as const)
        : ("Child task" as const),
    };
  });
}

export type ActivityFilter = "all" | "active" | "attention" | "completed";
export function matchesActivity(
  record: TeamActivity,
  filter: ActivityFilter,
  now = Date.now(),
) {
  if (filter === "active")
    return record.state !== "unknown" && !terminalRun(record.state);
  if (filter === "attention") return attentionRun(record.state);
  if (filter === "completed")
    return (
      record.state === "succeeded" &&
      !!record.run?.completedAt &&
      now - Date.parse(record.run.completedAt) < 86_400_000
    );
  return true;
}
