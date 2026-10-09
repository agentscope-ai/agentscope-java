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

import { describe, expect, it } from "vitest";
import type { AgentTask } from "@/api/collaboration";
import type { RuntimeSession } from "@/features/operate/api";
import {
  buildAgentActivities,
  isWorking,
  needsAttention,
  resultSummary,
  sessionStatus,
} from "./agentActivity";
import { collectActivityPages } from "./useAgentActivity";
import { canEditAgentDefinition } from "./agentAccess";
import type { AgentDefinition } from "@/api/agents";

const task = (patch: Partial<AgentTask> = {}) =>
  ({
    id: "t1",
    agentId: "a",
    issueId: "i",
    status: "completed",
    triggerType: "assignment",
    createdAt: "2026-09-07T01:00:00Z",
    ...patch,
  }) as AgentTask;
const session = (patch: Partial<RuntimeSession> = {}) =>
  ({
    id: "s1",
    sessionId: "native-1",
    agentName: "a",
    namespace: "default",
    phase: "idle",
    ...patch,
  }) as RuntimeSession;

describe("Agent activity aggregation", () => {
  it("shows one work item for a task and its explicitly linked sessions", () => {
    const result = buildAgentActivities(
      [task()],
      [
        session({ originType: "agent-task", originRef: "t1" }),
        session({ id: "s2", originType: "agent-task", originRef: "t1" }),
      ],
    );
    expect(result).toHaveLength(1);
    expect(result[0].sessions).toHaveLength(2);
    expect(result[0].status).toBe("completed");
  });
  it.each(["s1", "native-1"])(
    "matches a task session reference %s",
    (sessionId) => {
      expect(
        buildAgentActivities([task({ sessionId })], [session()]),
      ).toHaveLength(1);
      expect(
        buildAgentActivities([task({ sessionId })], [session()])[0].sessions,
      ).toHaveLength(1);
    },
  );
  it("never joins unrelated work because it shares an Issue or timestamp", () => {
    const result = buildAgentActivities(
      [task(), task({ id: "t2" })],
      [session({ snapshot: { messageCount: 2 }, startedAt: task().createdAt })],
    );
    expect(result).toHaveLength(3);
  });
  it("gives explicit task origin precedence over a conflicting session identifier", () => {
    const result = buildAgentActivities(
      [task({ sessionId: "s1" }), task({ id: "t2" })],
      [session({ originType: "agent-task", originRef: "t2" })],
    );
    expect(
      result.find((item) => item.task?.id === "t1")?.sessions,
    ).toHaveLength(0);
    expect(
      result.find((item) => item.task?.id === "t2")?.sessions,
    ).toHaveLength(1);
  });
  it("keeps idle heartbeat-only sessions out of work history", () => {
    expect(
      buildAgentActivities(
        [],
        [session(), session({ id: "s2", phase: "active" })],
      ),
    ).toHaveLength(0);
    expect(isWorking(sessionStatus(session({ phase: "active" })))).toBe(false);
    expect(sessionStatus(session({ busy: true }))).toBe("running");
  });
  it("retains native work without inventing completion from idle state", () => {
    const result = buildAgentActivities(
      [],
      [session({ originType: "channel", originRef: "c1" })],
    );
    expect(result[0].source).toBe("Channel");
    expect(result[0].status).toBe("idle");
  });
  it("orders by actual task progress and does not advance work timestamps with session heartbeats", () => {
    const result = buildAgentActivities(
      [
        task({ completedAt: "2026-09-07T02:00:00Z" }),
        task({ id: "t2", completedAt: "2026-09-07T03:00:00Z" }),
      ],
      [
        session({
          originType: "agent-task",
          originRef: "t1",
          lastActiveAt: "2026-09-08T00:00:00Z",
        }),
      ],
    );
    expect(result.map((item) => item.task?.id)).toEqual(["t2", "t1"]);
  });
  it("deduplicates overlapping pages and preserves unknown outcomes", () => {
    expect(
      buildAgentActivities([task(), task()], [session(), session()]),
    ).toHaveLength(1);
    expect(needsAttention("failed")).toBe(true);
    expect(needsAttention("cancelled")).toBe(false);
    expect(resultSummary({ output: [{ text: "Report ready" }] })).toBe(
      "Report ready",
    );
  });
  it("reads past the old 100-record limit", async () => {
    const pages = [Array.from({ length: 100 }, (_, i) => i), [100, 101]];
    const offsets: number[] = [];
    const items = await collectActivityPages(async (offset) => {
      offsets.push(offset);
      return pages[offset / 100];
    });
    expect(offsets).toEqual([0, 100]);
    expect(items).toHaveLength(102);
  });
  it("propagates pagination errors and cancellation instead of reporting partial totals", async () => {
    await expect(
      collectActivityPages(async () => {
        throw new Error("offline");
      }),
    ).rejects.toThrow("offline");
    const controller = new AbortController();
    controller.abort();
    await expect(
      collectActivityPages(async () => [], controller.signal),
    ).rejects.toThrow();
  });
});

describe("Definition permissions", () => {
  const agent = { scope: "user", ownerId: "owner" } as AgentDefinition;
  it("permits the owner fallback and explicit EDIT access", () => {
    expect(canEditAgentDefinition(agent, "owner")).toBe(true);
    expect(
      canEditAgentDefinition(
        { ...agent, tierForCurrentUser: "EDIT" },
        "collaborator",
      ),
    ).toBe(true);
  });
  it("keeps global, run-only and clone-only definitions read-only", () => {
    expect(canEditAgentDefinition({ ...agent, scope: "global" }, "owner")).toBe(
      false,
    );
    expect(
      canEditAgentDefinition({ ...agent, tierForCurrentUser: "RUN" }, "owner"),
    ).toBe(false);
    expect(
      canEditAgentDefinition(
        { ...agent, tierForCurrentUser: "CLONE" },
        "owner",
      ),
    ).toBe(false);
    expect(canEditAgentDefinition(agent, "stranger")).toBe(false);
  });
});

describe("Retry lineage", () => {
  it("folds a successful retry into the original work and retains both contexts", () => {
    const original = task({
      status: "failed",
      completedAt: "2026-09-07T02:00:00Z",
    });
    const retry = task({
      id: "t2",
      retryOfTaskId: "t1",
      triggerType: "retry",
      createdAt: "2026-09-07T03:00:00Z",
      completedAt: "2026-09-07T04:00:00Z",
    });
    const result = buildAgentActivities(
      [retry, original],
      [
        session({ originType: "agent-task", originRef: "t1" }),
        session({ id: "s2", originType: "agent-task", originRef: "t2" }),
      ],
    );
    expect(result).toHaveLength(1);
    expect(result[0].status).toBe("completed");
    expect(result[0].source).toBe("Issue");
    expect(result[0].attempts?.map((item) => item.id)).toEqual(["t1", "t2"]);
    expect(result[0].sessions).toHaveLength(2);
    expect(needsAttention(result[0].status)).toBe(false);
  });
  it("keeps a new rerun separate from a retry and tolerates missing parents", () => {
    const result = buildAgentActivities(
      [
        task(),
        task({ id: "t2", rerunOfTaskId: "t1" }),
        task({ id: "t3", retryOfTaskId: "missing" }),
      ],
      [],
    );
    expect(result).toHaveLength(3);
  });
});
