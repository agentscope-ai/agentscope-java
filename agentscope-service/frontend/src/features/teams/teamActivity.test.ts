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
import type { OrchestrationRun } from "@/api/orchestration";
import {
  buildTaskSteps,
  groupTeamActivity,
  matchesActivity,
  type TeamTask,
} from "./teamActivity";
import { normalizeTeamTab } from "./teamNavigation";
const task = (id: string, fields: Partial<TeamTask> = {}): TeamTask => ({
  id,
  tenant: "t",
  namespace: "n",
  issueId: "issue",
  orchestrationRunId: "run",
  runNodeId: "node",
  agentId: "agent",
  status: "completed",
  priority: 0,
  triggerType: "issue",
  originator: { type: "human" },
  version: 1,
  createdAt: "2026-09-07T01:00:00Z",
  ...fields,
});
const run = (fields: Partial<OrchestrationRun> = {}): OrchestrationRun => ({
  id: "run",
  tenant: "t",
  namespace: "n",
  rootIssueId: "issue",
  mode: "adaptive",
  triggerType: "issue",
  state: "running",
  version: 1,
  createdAt: "2026-09-07T01:00:00Z",
  ...fields,
});
describe("Team collaboration records", () => {
  it("uses authoritative Run state even when every member task is complete", () => {
    const [record] = groupTeamActivity(
      [task("lead"), task("worker")],
      [run()],
      { issue: "Research goal" },
    );
    expect(record.state).toBe("running");
    expect(record.title).toBe("Research goal");
    expect(matchesActivity(record, "completed")).toBe(false);
  });
  it("keeps reruns of the same Issue separate and sorts by recent progress", () => {
    const records = groupTeamActivity(
      [
        task("a"),
        task("b", {
          orchestrationRunId: "rerun",
          completedAt: "2026-09-07T03:00:00Z",
        }),
      ],
      [run(), run({ id: "rerun", rerunOfRunId: "run" })],
    );
    expect(records.map((r) => r.id)).toEqual(["rerun", "run"]);
  });
  it("does not infer success or merge unrelated tasks when the Run is missing", () => {
    const records = groupTeamActivity(
      [
        task("a", { orchestrationRunId: "" }),
        task("b", { orchestrationRunId: "" }),
      ],
      [],
    );
    expect(records).toHaveLength(2);
    expect(records.every((r) => r.state === "unknown")).toBe(true);
    expect(records.some((r) => matchesActivity(r, "active"))).toBe(false);
  });
  it("reports failed Run output independently of completed Lead tasks", () => {
    const [record] = groupTeamActivity(
      [task("lead")],
      [run({ state: "failed", failureMessage: "Worker could not deliver" })],
    );
    expect(record.summary).toBe("Worker could not deliver");
    expect(matchesActivity(record, "attention")).toBe(true);
  });
  it("counts last-day successes by completedAt, not task creation time", () => {
    const now = Date.parse("2026-09-07T12:00:00Z");
    const [record] = groupTeamActivity(
      [task("lead")],
      [run({ state: "succeeded", completedAt: "2026-09-07T02:00:00Z" })],
    );
    expect(matchesActivity(record, "completed", now)).toBe(true);
    expect(matchesActivity(record, "completed", now + 86_400_000)).toBe(false);
  });
});
describe("Recorded task relationships", () => {
  it("folds explicit retries and preserves delegation through their original task", () => {
    const steps = buildTaskSteps([
      task("lead"),
      task("z-worker", { delegatedFromTaskId: "lead", status: "failed" }),
      task("a-retry", { retryOfTaskId: "z-worker" }),
    ]);
    expect(steps).toHaveLength(2);
    expect(steps[1].tasks.map((t) => t.id)).toEqual(["z-worker", "a-retry"]);
    expect(steps[1].latest.id).toBe("a-retry");
    expect(steps[1].parentId).toBe("lead");
  });
  it("never invents edges between Lead and Worker from roles or timestamps", () => {
    const steps = buildTaskSteps([
      task("lead", { leaderTask: true }),
      task("worker", { teamRole: "worker" }),
    ]);
    expect(steps.every((s) => !s.parentId)).toBe(true);
  });
  it("does not fold reruns and handles missing parents and retry cycles", () => {
    expect(
      buildTaskSteps([
        task("a"),
        task("b", { rerunOfTaskId: "a", parentTaskId: "missing" }),
      ]),
    ).toHaveLength(2);
    const steps = buildTaskSteps([
      task("a", { retryOfTaskId: "b" }),
      task("b", { retryOfTaskId: "a" }),
    ]);
    expect(steps).toHaveLength(1);
    expect(steps[0].tasks).toHaveLength(2);
  });
});
it("preserves old Team links through canonical tabs", () => {
  expect(normalizeTeamTab("members")).toBe("orchestration");
  expect(normalizeTeamTab("coordination")).toBe("orchestration");
  expect(normalizeTeamTab("endpoints")).toBe("connections");
  expect(normalizeTeamTab("bad")).toBe("overview");
});
