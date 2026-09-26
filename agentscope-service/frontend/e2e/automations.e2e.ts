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

import { test, expect, type Page } from "@playwright/test";

const automationId = "00000000-0000-4000-8000-000000000010";
const agentId = "00000000-0000-4000-8000-000000000011";
const triggerId = "00000000-0000-4000-8000-000000000012";
const runId = "00000000-0000-4000-8000-000000000013";
const createdAt = "2026-09-08T01:00:00Z";
async function mockAutomations(page: Page) {
  const rule = {
    id: automationId,
    tenant: "t",
    namespace: "n",
    name: "Engineering digest",
    enabled: true,
    version: 4,
    actionType: "create_issue",
    createdAt,
    updatedAt: createdAt,
    execution: {
      runbook: "Read sources and produce a concise report.",
      assigneeType: "agent",
      assigneeRef: agentId,
      outputMode: "create_issue",
      completionPolicy: "review",
      concurrencyPolicy: "skip",
      queueTimeoutSeconds: 3600,
      runTimeoutSeconds: 3600,
    },
    triggers: [
      {
        id: triggerId,
        type: "cron",
        schedule: "0 9 * * 1-5",
        timezone: "Asia/Shanghai",
        enabled: true,
      },
    ],
  };
  const state = {
    writes: [] as any[],
    triggerKeys: [] as string[],
    failTrigger: false,
    failSave: false,
    rule,
    status: "waiting",
    waitReason: "review",
  };
  const token = `test.${Buffer.from(JSON.stringify({ username: "alice", roles: ["admin"] })).toString("base64url")}.test`;
  await page.addInitScript(
    (token) => localStorage.setItem("claw_token", token),
    token,
  );
  await page.route("**/api/**", async (route) => {
    const req = route.request();
    const url = new URL(req.url());
    const path = url.pathname;
    if (!path.startsWith("/api/")) return route.continue();
    const json = (body: unknown, status = 200) =>
      route.fulfill({
        status,
        contentType: "application/json",
        body: JSON.stringify(body),
      });
    if (path === "/api/auth/me")
      return json({
        userId: "alice-id",
        username: "alice",
        roles: ["admin"],
        isAdmin: true,
      });
    if (path === "/api/v1/me/scope")
      return json({
        tenant: "t",
        namespace: "n",
        mode: "single",
        selectorVisible: false,
      });
    if (path === "/api/v1/agents")
      return json({
        items: [
          {
            id: agentId,
            agentKey: "researcher",
            displayName: "Research Agent",
            status: "active",
            tenant: "t",
            namespace: "n",
          },
        ],
      });
    if (path === "/api/v1/teams")
      return json({
        items: [{ id: "team-1", name: "Research team", status: "active" }],
      });
    if (path === "/api/v1/automations/schedule-preview")
      return json({
        nextRuns: [
          "2026-09-09T01:00:00Z",
          "2026-09-10T01:00:00Z",
          "2026-09-11T01:00:00Z",
        ],
      });
    if (
      (path === "/api/v1/automations" && req.method() === "POST") ||
      (path === `/api/v1/automations/${automationId}` &&
        req.method() === "PATCH")
    ) {
      state.writes.push(req.postDataJSON());
      if (state.failSave)
        return json({ error: "Configuration changed. Reload and retry." }, 409);
      Object.assign(rule, req.postDataJSON());
      return json({ automation: rule });
    }
    if (path === "/api/v1/automations") return json({ items: [rule] });
    if (path === `/api/v1/automations/${automationId}`)
      return json({ automation: rule });
    if (path.endsWith("/trigger")) {
      state.triggerKeys.push(req.headers()["idempotency-key"]);
      if (state.failTrigger)
        return json({ error: "Connection interrupted" }, 503);
      return json({ run: { id: runId } });
    }
    const run = {
      id: runId,
      automationId,
      status: state.status,
      waitReason: state.waitReason,
      source: "manual",
      version: 5,
      createdAt,
      executionCompletedAt: createdAt,
      output: { summary: "Research complete" },
      snapshot: rule,
    };
    if (path.endsWith(`/runs/${runId}`))
      return json({
        run,
        issue: { id: "issue-1", title: "Engineering digest" },
        tasks: [],
        artifacts: [],
      });
    if (path.endsWith("/runs")) return json({ items: [run] });
    return json({ items: [], summary: {}, agents: [] });
  });
  return state;
}

test("creates an assigned runbook with schedule, webhook and subscription", async ({
  page,
}) => {
  const state = await mockAutomations(page);
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.goto("/work/automations");
  await page.getByRole("button", { name: "New automation" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("Name", { exact: true }).fill("Daily research");
  await dialog
    .getByLabel("Runbook")
    .fill("Check build results and summarize failures.");
  await dialog.getByRole("combobox", { name: "Agent", exact: true }).click();
  await dialog.getByRole("option", { name: /Research Agent/ }).click();
  await dialog.getByLabel("Time zone", { exact: true }).fill("Asia/Shanghai");
  await dialog.getByLabel("Subscribe me to each issue").check();
  await dialog.getByRole("button", { name: "Webhook", exact: true }).click();
  await dialog.getByLabel("Webhook event filters").fill("build.completed");
  await expect(dialog.getByText("Next runs", { exact: true })).toBeVisible();
  await page.screenshot({
    path: "/tmp/agentscope-automation-editor.png",
    fullPage: true,
  });
  await dialog
    .getByRole("button", { name: "Create automation", exact: true })
    .click();
  await expect(dialog).not.toBeVisible();
  expect(state.writes[0]).toMatchObject({
    name: "Daily research",
    execution: {
      assigneeRef: agentId,
      subscribers: ["alice-id"],
      runbook: "Check build results and summarize failures.",
    },
    triggers: [
      { type: "cron", timezone: "Asia/Shanghai" },
      { type: "webhook", events: ["build.completed"] },
    ],
  });
  expect(errors).toEqual([]);
});

test("keeps the editor and draft on conflict; run only uses automatic completion", async ({
  page,
}) => {
  const state = await mockAutomations(page);
  state.failSave = true;
  await page.goto(`/work/automations?automation=${automationId}`);
  await page.getByRole("button", { name: "Edit", exact: true }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("radio", { name: /Run only/ }).check();
  await dialog.getByRole("button", { name: "Save changes" }).click();
  await expect(dialog.getByRole("alert")).toContainText(
    "Configuration changed",
  );
  await expect(dialog.getByLabel("Runbook")).toHaveValue(
    state.rule.execution.runbook,
  );
  expect(state.writes[0]).toMatchObject({
    expectedVersion: 4,
    execution: { outputMode: "run_only", completionPolicy: "automatic" },
  });
});

test("retries a failed manual request with the same key and shows real review state", async ({
  page,
}) => {
  const state = await mockAutomations(page);
  state.failTrigger = true;
  await page.goto(
    `/work/automations?automation=${automationId}&tenant=t&namespace=n`,
  );
  await page.getByRole("button", { name: "Run now", exact: true }).click();
  await expect(page.getByRole("alert")).toContainText("Connection interrupted");
  state.failTrigger = false;
  await page.getByRole("button", { name: "Run now", exact: true }).click();
  await expect(
    page.getByText(
      "Execution finished. Open the issue to review and accept the result.",
    ),
  ).toBeVisible();
  await expect(
    page.getByText("Awaiting review", { exact: true }),
  ).toBeVisible();
  await expect(page.getByText(/Research complete/)).toBeVisible();
  expect(state.triggerKeys.length).toBe(2);
  expect(state.triggerKeys[0]).toBeTruthy();
  expect(state.triggerKeys[1]).toBe(state.triggerKeys[0]);
  await expect(
    page.getByRole("link", { name: "Open issue: Engineering digest" }),
  ).toHaveAttribute("href", "/work/issues/issue-1?tenant=t&namespace=n");
  await page.screenshot({
    path: "/tmp/agentscope-automation-run.png",
    fullPage: true,
  });
});
