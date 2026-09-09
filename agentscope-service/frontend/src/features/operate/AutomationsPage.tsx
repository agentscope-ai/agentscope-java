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

import { useRef, useState } from "react";
import { useSearchParams } from "react-router-dom";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  ArrowLeft,
  Archive,
  Clock3,
  Copy,
  Pencil,
  Play,
  Plus,
  Power,
  Workflow,
} from "lucide-react";
import {
  archiveAutomation,
  getAutomation,
  listAutomations,
  listAutomationDeliveries,
  listAutomationRuns,
  replayAutomationDelivery,
  rotateAutomationSecret,
  triggerAutomation,
  updateAutomation,
  type Automation,
  type AutomationSaved,
} from "@/api/automations";
import { useControlPlaneScope } from "@/app/ScopeContext";
import { AgentIdentity } from "@/components/AgentPicker";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Dialog,
  DialogBody,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  WorkEmpty,
  WorkLoadingRows,
  WorkPage,
  WorkPageHeader,
  WorkPanel,
  WorkPanelHeader,
} from "@/features/work/WorkSurface";
import { AutomationEditor } from "@/features/automations/AutomationEditor";
import { automationError } from "@/features/automations/form";
import {
  automationDate as date,
  displayValue,
  RunBadge,
  RunDetail,
} from "@/features/automations/RunDetail";
import { formatRelative } from "@/lib/format";

export default function AutomationsPage() {
  const scope = useControlPlaneScope();
  const qc = useQueryClient();
  const [params, setParams] = useSearchParams();
  const id = params.get("automation") || "";
  const runId = params.get("run") || "";
  const [editor, setEditor] = useState<Automation | "new" | null>(null);
  const [search, setSearch] = useState("");
  const [filter, setFilter] = useState("all");
  const [listOffset, setListOffset] = useState(0);
  const [runOffset, setRunOffset] = useState(0);
  const [deliveryOffset, setDeliveryOffset] = useState(0);
  const [tab, setTab] = useState<"runs" | "deliveries">("runs");
  const [secret, setSecret] = useState("");
  const [archiveTarget, setArchiveTarget] = useState<Automation>();
  const operationKeys = useRef(new Map<string, string>());
  const navigate = (automation?: string, run?: string) => {
    setParams((current) => {
      const next = new URLSearchParams(current);
      next.delete("automation");
      next.delete("run");
      if (automation) next.set("automation", automation);
      if (run) next.set("run", run);
      return next;
    });
    setRunOffset(0);
    setDeliveryOffset(0);
  };
  const invalidate = () => {
    for (const key of [
      "automations",
      "automation",
      "automation-runs",
      "automation-run",
      "automation-deliveries",
      "issues",
    ])
      void qc.invalidateQueries({ queryKey: [key] });
  };
  const action = useMutation({
    mutationFn: async ({
      key,
      fn,
    }: {
      key: string;
      fn: (key: string) => Promise<unknown>;
    }) => {
      let token = operationKeys.current.get(key);
      if (!token) {
        token = crypto.randomUUID();
        operationKeys.current.set(key, token);
      }
      const value = await fn(token);
      operationKeys.current.delete(key);
      return value;
    },
    onSuccess: invalidate,
  });
  const command = (key: string, fn: (key: string) => Promise<unknown>) => {
    if (!action.isPending) action.mutate({ key, fn });
  };
  const list = useQuery({
    queryKey: ["automations", scope.tenant, scope.namespace, listOffset],
    queryFn: () => listAutomations(scope.tenant, scope.namespace, listOffset),
    enabled: !id,
    refetchInterval: 10000,
  });
  const detail = useQuery({
    queryKey: ["automation", scope.tenant, scope.namespace, id],
    queryFn: () => getAutomation(id),
    enabled: !!id,
    refetchInterval: 5000,
  });
  const runs = useQuery({
    queryKey: ["automation-runs", id, runOffset],
    queryFn: () => listAutomationRuns(id, runOffset),
    enabled: !!id && !runId && tab === "runs",
    refetchInterval: 2000,
  });
  const deliveries = useQuery({
    queryKey: ["automation-deliveries", id, deliveryOffset],
    queryFn: () => listAutomationDeliveries(id, deliveryOffset),
    enabled: !!id && !runId && tab === "deliveries",
    refetchInterval: 5000,
  });
  const rule = detail.data?.automation;
  const saved = (result: AutomationSaved) => {
    setEditor(null);
    setSecret(result.webhookSecret || "");
    navigate(result.automation.id);
    invalidate();
  };
  const runNow = (item: Automation) =>
    command(`run:${item.id}`, async (key) => {
      const next = await triggerAutomation(
        item.id,
        key,
        undefined,
        !item.enabled,
      );
      navigate(item.id, next.run.id);
    });
  const rows = (list.data?.items || []).filter(
    (item) =>
      (!search ||
        `${item.name} ${item.description || ""}`
          .toLowerCase()
          .includes(search.toLowerCase())) &&
      (filter === "all" || item.enabled === (filter === "enabled")),
  );
  return (
    <WorkPage>
      {id && (
        <Button
          variant="ghost"
          size="sm"
          onClick={() => navigate(runId ? id : undefined)}
        >
          <ArrowLeft className="h-4 w-4" />
          {runId ? "Run history" : "Automations"}
        </Button>
      )}
      <WorkPageHeader
        title={id ? rule?.name || "Automation" : "Automations"}
        description={
          id
            ? rule?.description ||
              "Repeatable work with a complete execution history."
            : "Put recurring work in the hands of your Agents and Teams."
        }
        actions={
          !id ? (
            <Button onClick={() => setEditor("new")}>
              <Plus className="h-4 w-4" /> New automation
            </Button>
          ) : rule && !rule.archivedAt ? (
            <>
              <Button
                variant="outline"
                onClick={() => setEditor(rule)}
                disabled={rule.actionType !== "create_issue"}
              >
                <Pencil className="h-4 w-4" /> Edit
              </Button>
              <Button disabled={action.isPending} onClick={() => runNow(rule)}>
                <Play className="h-4 w-4" />
                {rule.enabled ? "Run now" : "Test run"}
              </Button>
            </>
          ) : undefined
        }
      />
      {action.error && (
        <p
          role="alert"
          className="rounded-xl bg-red-50 p-4 text-sm text-red-700"
        >
          {automationError(action.error)}
        </p>
      )}
      {!id ? (
        <WorkPanel>
          <div className="flex flex-wrap gap-3 border-b border-slate-200 p-4">
            <Input
              aria-label="Search automations"
              placeholder="Search automations…"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              className="max-w-sm"
            />
            <select
              aria-label="Filter automations"
              className="rounded-lg border border-slate-200 px-3 text-sm"
              value={filter}
              onChange={(e) => setFilter(e.target.value)}
            >
              <option value="all">All statuses</option>
              <option value="enabled">Enabled</option>
              <option value="paused">Paused</option>
            </select>
          </div>
          {list.isLoading ? (
            <WorkLoadingRows rows={4} />
          ) : list.error ? (
            <WorkEmpty
              title="Could not load automations"
              description={automationError(list.error)}
            />
          ) : rows.length === 0 ? (
            <WorkEmpty
              title={
                list.data?.items.length
                  ? "No matching automations"
                  : "Your repeatable work starts here"
              }
              description="Create a runbook, choose an assignee and set a schedule or webhook."
              action={
                <Button onClick={() => setEditor("new")}>
                  Create automation
                </Button>
              }
            />
          ) : (
            <div className="divide-y divide-slate-100">
              {rows.map((item) => (
                <article
                  key={item.id}
                  className="flex flex-wrap items-center gap-4 p-5"
                >
                  <Workflow className="h-5 w-5 text-slate-400" />
                  <button
                    className="min-w-0 flex-1 text-left"
                    onClick={() => navigate(item.id)}
                  >
                    <span className="font-semibold text-slate-900">
                      {item.name}
                    </span>
                    <span className="mt-1 block text-sm text-slate-500">
                      {item.description ||
                        item.execution?.runbook.slice(0, 100)}
                    </span>
                    <span className="mt-2 flex flex-wrap gap-4 text-xs text-slate-400">
                      <span>{item.triggers?.length || 0} triggers</span>
                      <span>Next: {date(item.nextRunAt)}</span>
                      <span>
                        {item.lastRunAt
                          ? `Last ran ${formatRelative(item.lastRunAt)}`
                          : "Never run"}
                      </span>
                    </span>
                  </button>
                  <Badge tone={item.enabled ? "success" : "default"}>
                    {item.enabled ? "Enabled" : "Paused"}
                  </Badge>
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={action.isPending}
                    onClick={() => runNow(item)}
                  >
                    <Play className="h-3.5 w-3.5" />
                    {item.enabled ? "Run now" : "Test run"}
                  </Button>
                </article>
              ))}
            </div>
          )}
          <div className="flex justify-between border-t border-slate-100 p-3">
            <Button
              variant="ghost"
              size="sm"
              disabled={listOffset === 0}
              onClick={() => setListOffset((v) => Math.max(0, v - 100))}
            >
              Previous
            </Button>
            <Button
              variant="ghost"
              size="sm"
              disabled={(list.data?.items.length || 0) < 100}
              onClick={() => setListOffset((v) => v + 100)}
            >
              Next
            </Button>
          </div>
        </WorkPanel>
      ) : detail.isLoading ? (
        <WorkLoadingRows rows={4} />
      ) : !rule ? (
        <WorkEmpty
          title="Automation unavailable"
          description={automationError(detail.error)}
        />
      ) : (
        <>
          {!runId && (
            <WorkPanel>
              <div className="flex flex-wrap items-center gap-3 border-b border-slate-100 p-5">
                <Badge tone={rule.enabled ? "success" : "default"}>
                  {rule.archivedAt
                    ? "Archived"
                    : rule.enabled
                      ? "Enabled"
                      : "Paused"}
                </Badge>
                <span className="flex-1 text-sm text-slate-500">
                  {rule.execution?.outputMode === "run_only"
                    ? "Run only"
                    : "Creates issues"}{" "}
                  ·{" "}
                  {rule.execution?.assigneeType === "agent" ? (
                    <AgentIdentity
                      agentId={rule.execution.assigneeRef}
                      showId={false}
                    />
                  ) : rule.execution?.assigneeType === "team" ? (
                    "Team execution"
                  ) : (
                    "Advanced action"
                  )}
                </span>
                {!rule.archivedAt && (
                  <>
                    <Button
                      variant="ghost"
                      size="sm"
                      disabled={action.isPending}
                      onClick={() =>
                        command(`toggle:${id}`, () =>
                          updateAutomation(id, {
                            enabled: !rule.enabled,
                            expectedVersion: rule.version,
                          }),
                        )
                      }
                    >
                      <Power className="h-4 w-4" />
                      {rule.enabled ? "Pause" : "Resume"}
                    </Button>
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => setArchiveTarget(rule)}
                    >
                      <Archive className="h-4 w-4" /> Archive
                    </Button>
                  </>
                )}
              </div>
              <div className="grid gap-6 p-5 lg:grid-cols-2">
                <div>
                  <h2 className="mb-2 text-sm font-semibold">Runbook</h2>
                  <p className="max-h-64 overflow-auto whitespace-pre-wrap text-sm leading-6 text-slate-600">
                    {rule.execution?.runbook ||
                      "This rule uses the advanced action configuration. Edit it through the API."}
                  </p>
                </div>
                <div className="space-y-3">
                  <h2 className="text-sm font-semibold">Triggers</h2>
                  {rule.triggers?.map((t) => (
                    <div key={t.id} className="rounded-xl bg-slate-50 p-3">
                      <div className="flex justify-between text-sm">
                        <span>
                          {t.type === "cron"
                            ? `${t.schedule} · ${t.timezone}`
                            : "Webhook"}
                        </span>
                        <span className="text-xs text-slate-500">
                          {t.enabled ? "Enabled" : "Disabled"}
                        </span>
                      </div>
                      {t.type === "cron" ? (
                        <p className="mt-1 text-xs text-slate-500">
                          Next: {date(t.nextRunAt)}
                        </p>
                      ) : (
                        <>
                          <code className="mt-2 block break-all text-xs">
                            {window.location.origin}/hooks/v1/automations/{id}/
                            {t.id}
                          </code>
                          <p className="mt-1 text-xs text-slate-500">
                            Events: {t.events?.join(", ") || "All events"}. Send
                            X-Automation-Secret and Idempotency-Key headers.
                          </p>
                        </>
                      )}
                    </div>
                  ))}
                  {rule.triggers?.some((t) => t.type === "webhook") &&
                    !rule.archivedAt && (
                      <Button
                        variant="outline"
                        size="sm"
                        disabled={action.isPending}
                        onClick={() =>
                          command(`rotate:${id}`, async () => {
                            const updated = await rotateAutomationSecret(
                              id,
                              rule.version,
                            );
                            setSecret(updated.webhookSecret || "");
                          })
                        }
                      >
                        Generate new webhook credential
                      </Button>
                    )}
                  <p className="text-xs text-slate-500">
                    Runs inherit the assignee’s workspace and execution
                    environment. Completed executions awaiting review do not
                    block the next schedule.
                  </p>
                </div>
              </div>
            </WorkPanel>
          )}
          {runId ? (
            <WorkPanel>
              <WorkPanelHeader title="Run details" />
              <RunDetail
                automationId={id}
                runId={runId}
                command={command}
                openRun={(run) => navigate(id, run)}
              />
            </WorkPanel>
          ) : (
            <WorkPanel>
              <div className="flex gap-2 border-b border-slate-200 p-3">
                <Button
                  variant={tab === "runs" ? "secondary" : "ghost"}
                  size="sm"
                  onClick={() => setTab("runs")}
                >
                  Run history
                </Button>
                <Button
                  variant={tab === "deliveries" ? "secondary" : "ghost"}
                  size="sm"
                  onClick={() => setTab("deliveries")}
                >
                  Webhook deliveries
                </Button>
              </div>
              {tab === "runs" ? (
                runs.isLoading ? (
                  <WorkLoadingRows rows={3} />
                ) : runs.error ? (
                  <WorkEmpty
                    title="Could not load runs"
                    description={automationError(runs.error)}
                  />
                ) : !runs.data?.items.length ? (
                  <WorkEmpty
                    title="No runs yet"
                    description="Run the automation now to check its first result."
                  />
                ) : (
                  <div className="divide-y divide-slate-100">
                    {runs.data.items.map((run) => (
                      <button
                        key={run.id}
                        onClick={() => navigate(id, run.id)}
                        className="flex w-full flex-wrap items-center gap-4 p-5 text-left hover:bg-slate-50"
                      >
                        <Clock3 className="h-4 w-4 text-slate-400" />
                        <span className="min-w-0 flex-1">
                          <span className="block text-sm font-medium">
                            {date(run.createdAt)}
                          </span>
                          <span className="text-xs text-slate-500">
                            {run.source || run.triggerType}
                            {run.errorMessage ? ` · ${run.errorMessage}` : ""}
                          </span>
                        </span>
                        <RunBadge run={run} />
                      </button>
                    ))}
                  </div>
                )
              ) : deliveries.isLoading ? (
                <WorkLoadingRows rows={3} />
              ) : deliveries.error ? (
                <WorkEmpty
                  title="Could not load deliveries"
                  description={automationError(deliveries.error)}
                />
              ) : !deliveries.data?.items.length ? (
                <WorkEmpty
                  title="No webhook deliveries"
                  description="Incoming events and their outcomes will appear here."
                />
              ) : (
                <div className="divide-y divide-slate-100">
                  {deliveries.data.items.map((d) => (
                    <div key={d.id} className="space-y-2 p-5">
                      <div className="flex flex-wrap items-center gap-3">
                        <span className="flex-1 text-sm font-medium">
                          {d.event}{" "}
                          <span className="font-normal text-slate-400">
                            · {date(d.createdAt)}
                          </span>
                        </span>
                        <Badge>{d.status}</Badge>
                        {d.runId && (
                          <Button
                            size="sm"
                            variant="ghost"
                            onClick={() => navigate(id, d.runId)}
                          >
                            Open run
                          </Button>
                        )}
                        {d.status !== "queued" && d.status !== "rejected" && (
                          <Button
                            disabled={action.isPending}
                            size="sm"
                            variant="outline"
                            onClick={() =>
                              command(`replay:${d.id}`, (key) =>
                                replayAutomationDelivery(id, d.id, key),
                              )
                            }
                          >
                            Replay
                          </Button>
                        )}
                      </div>
                      {d.reason && (
                        <p className="text-xs text-slate-500">{d.reason}</p>
                      )}
                      <details className="text-xs">
                        <summary className="cursor-pointer">Payload</summary>
                        <pre className="mt-2 max-h-64 overflow-auto whitespace-pre-wrap break-words bg-slate-50 p-3">
                          {displayValue(d.input)}
                        </pre>
                      </details>
                    </div>
                  ))}
                </div>
              )}
              <div className="flex justify-between border-t border-slate-100 p-3">
                <Button
                  variant="ghost"
                  size="sm"
                  disabled={(tab === "runs" ? runOffset : deliveryOffset) === 0}
                  onClick={() =>
                    tab === "runs"
                      ? setRunOffset((v) => Math.max(0, v - 25))
                      : setDeliveryOffset((v) => Math.max(0, v - 25))
                  }
                >
                  Previous
                </Button>
                <Button
                  variant="ghost"
                  size="sm"
                  disabled={
                    (tab === "runs"
                      ? runs.data?.items.length || 0
                      : deliveries.data?.items.length || 0) < 25
                  }
                  onClick={() =>
                    tab === "runs"
                      ? setRunOffset((v) => v + 25)
                      : setDeliveryOffset((v) => v + 25)
                  }
                >
                  Next
                </Button>
              </div>
            </WorkPanel>
          )}
        </>
      )}
      {editor && (
        <AutomationEditor
          key={editor === "new" ? "new" : `${editor.id}:${editor.version}`}
          rule={editor === "new" ? undefined : editor}
          onClose={() => setEditor(null)}
          onSaved={saved}
        />
      )}
      <Dialog
        open={!!secret}
        onOpenChange={(open) => {
          if (!open) setSecret("");
        }}
      >
        <DialogContent size="md">
          <DialogHeader>
            <DialogTitle>Webhook credential</DialogTitle>
            <DialogDescription>
              This credential is shown once. Save it in your webhook sender;
              generating a new credential invalidates the previous one.
            </DialogDescription>
          </DialogHeader>
          <DialogBody className="space-y-4">
            <Input
              aria-label="Webhook credential"
              type="password"
              readOnly
              value={secret}
            />
            <Button onClick={() => void navigator.clipboard.writeText(secret)}>
              <Copy className="h-4 w-4" /> Copy credential
            </Button>
            <p className="text-sm text-slate-500">
              Send it as the X-Automation-Secret header. Also send a stable
              Idempotency-Key for each event.
            </p>
          </DialogBody>
        </DialogContent>
      </Dialog>
      <Dialog
        open={!!archiveTarget}
        onOpenChange={(open) => {
          if (!open) setArchiveTarget(undefined);
        }}
      >
        <DialogContent size="md">
          <DialogHeader>
            <DialogTitle>Archive automation?</DialogTitle>
            <DialogDescription>
              New triggers stop. Existing runs and their history are retained.
            </DialogDescription>
          </DialogHeader>
          <DialogBody>
            <Button
              disabled={action.isPending}
              onClick={() => {
                if (archiveTarget)
                  command(`archive:${archiveTarget.id}`, async () => {
                    await archiveAutomation(
                      archiveTarget.id,
                      archiveTarget.version,
                    );
                    setArchiveTarget(undefined);
                    navigate();
                  });
              }}
            >
              Archive automation
            </Button>
          </DialogBody>
        </DialogContent>
      </Dialog>
    </WorkPage>
  );
}
