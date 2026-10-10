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
import type { RuntimeSession } from "@/features/operate/api";

export interface AgentActivityRecord {
  id: string;
  task?: AgentTask;
  attempts?: AgentTask[];
  sessions: RuntimeSession[];
  source: string;
  status: string;
  timestamp?: string;
}

export function activityTime(task: AgentTask): string {
  return (
    task.completedAt || task.startedAt || task.dispatchedAt || task.createdAt
  );
}

export function sessionStatus(session: RuntimeSession): string {
  if (session.busy === true) return "running";
  if (session.phase === "compressing") return "compressing";
  // An active lifecycle alone is not evidence of an executing request.
  return session.phase || "unknown";
}

export function taskSource(trigger: string): string {
  const sources: Record<string, string> = {
    assignment: "Issue",
    comment: "Issue",
    mention: "Issue",
    delegation: "Delegation",
    orchestration_node: "Workflow",
    endpoint: "API",
    automation: "Automation",
    manual_replay: "Replay",
    retry: "Retry",
  };
  return sources[trigger] || trigger || "Unknown";
}

export function sessionSource(session: RuntimeSession): string {
  const sources: Record<string, string> = {
    endpoint: "API",
    channel: "Channel",
    "agent-task": "Task",
    chat: "Chat",
    runtime: "Runtime",
  };
  return (
    sources[session.originType || "runtime"] || session.originType || "Runtime"
  );
}

/** Join by explicit references only. A shared Issue or similar timestamp is not a work identity. */
export function buildAgentActivities(
  tasks: AgentTask[],
  sessions: RuntimeSession[],
): AgentActivityRecord[] {
  const uniqueTasks = [
    ...new Map(tasks.map((task) => [task.id, task])).values(),
  ];
  const uniqueSessions = [
    ...new Map(sessions.map((session) => [session.id, session])).values(),
  ];
  const linked = new Set<string>();
  const taskMap = new Map(uniqueTasks.map((task) => [task.id, task]));
  const byOrigin = new Map<string, RuntimeSession[]>();
  const bySessionRef = new Map<string, RuntimeSession[]>();
  for (const session of uniqueSessions) {
    if (session.originType === "agent-task" && session.originRef) {
      byOrigin.set(session.originRef, [
        ...(byOrigin.get(session.originRef) || []),
        session,
      ]);
    } else {
      for (const ref of new Set([session.id, session.sessionId])) {
        bySessionRef.set(ref, [...(bySessionRef.get(ref) || []), session]);
      }
    }
  }
  const groups = new Map<string, AgentTask[]>();
  for (const task of uniqueTasks) {
    let root = task;
    const visited = new Set<string>();
    while (root.retryOfTaskId && taskMap.has(root.retryOfTaskId)) {
      visited.add(root.id);
      if (visited.has(root.retryOfTaskId)) break;
      const parent = taskMap.get(root.retryOfTaskId)!;
      if (parent.agentId !== task.agentId) break;
      root = parent;
    }
    groups.set(root.id, [...(groups.get(root.id) || []), task]);
  }
  const records: AgentActivityRecord[] = [...groups.entries()].map(
    ([rootId, attempts]) => {
      attempts.sort(
        (a, b) =>
          (Date.parse(a.createdAt) || 0) - (Date.parse(b.createdAt) || 0) ||
          a.id.localeCompare(b.id),
      );
      const latest = attempts[attempts.length - 1];
      const related = new Map<string, RuntimeSession>();
      for (const attempt of attempts) {
        for (const session of [
          ...(byOrigin.get(attempt.id) || []),
          ...(bySessionRef.get(attempt.sessionId || "") || []),
        ]) {
          related.set(session.id, session);
          linked.add(session.id);
        }
      }
      return {
        id: `task:${rootId}`,
        task: latest,
        attempts,
        sessions: [...related.values()],
        source: taskSource(
          taskMap.get(rootId)?.triggerType || latest.triggerType,
        ),
        status: latest.status,
        timestamp: attempts
          .map(activityTime)
          .sort((a, b) => (Date.parse(b) || 0) - (Date.parse(a) || 0))[0],
      };
    },
  );
  for (const session of uniqueSessions) {
    if (linked.has(session.id)) continue;
    const hasWork =
      (session.snapshot?.messageCount ?? 0) > 0 ||
      session.busy === true ||
      (!!session.originType && session.originType !== "runtime");
    if (!hasWork) continue;
    records.push({
      id: `session:${session.id}`,
      sessions: [session],
      source: sessionSource(session),
      status: sessionStatus(session),
      timestamp: session.lastActiveAt || session.startedAt,
    });
  }
  return records.sort(
    (a, b) =>
      (Date.parse(b.timestamp || "") || 0) -
        (Date.parse(a.timestamp || "") || 0) || a.id.localeCompare(b.id),
  );
}

export function isWorking(status: string): boolean {
  return ["queued", "dispatched", "running", "waiting", "compressing"].includes(
    status,
  );
}
export function needsAttention(status: string): boolean {
  return ["failed", "blocked", "waiting_approval", "waiting_input"].includes(
    status,
  );
}
export function resultSummary(value: unknown): string {
  if (typeof value === "string") return value;
  if (Array.isArray(value))
    return value.map(resultSummary).filter(Boolean).join("\n");
  if (value && typeof value === "object") {
    const object = value as Record<string, unknown>;
    for (const key of [
      "summary",
      "text",
      "content",
      "output",
      "message",
      "result",
    ]) {
      const text = resultSummary(object[key]);
      if (text) return text;
    }
  }
  return "";
}
