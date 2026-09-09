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

import { useControlPlaneScope } from "@/app/ScopeContext";
import { Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import {
  getAutomationRun,
  cancelAutomationRun,
  rerunAutomationRun,
  type AutomationRun,
} from "@/api/automations";
import { AgentIdentity } from "@/components/AgentPicker";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { WorkEmpty, WorkLoadingRows } from "@/features/work/WorkSurface";
import { automationError, runLabel, terminalRun } from "./form";

export type AutomationCommand = (
  key: string,
  fn: (key: string) => Promise<unknown>,
) => void;
export const automationDate = (value?: string) =>
  value ? new Date(value).toLocaleString() : "—";
export const displayValue = (value: unknown) =>
  typeof value === "string" ? value : JSON.stringify(value, null, 2);
export function RunBadge({ run }: { run: AutomationRun }) {
  return (
    <Badge
      tone={
        run.status === "failed"
          ? "danger"
          : run.status === "completed"
            ? "success"
            : run.status === "waiting"
              ? "warning"
              : "default"
      }
    >
      {run.version === 0 && run.status === "completed"
        ? "Dispatched (legacy)"
        : runLabel(run.status, run.waitReason)}
    </Badge>
  );
}
export function RunDetail({
  automationId,
  runId,
  command,
  openRun,
}: {
  automationId: string;
  runId: string;
  command: AutomationCommand;
  openRun: (id: string) => void;
}) {
  const scope = useControlPlaneScope();
  const scopeQuery = new URLSearchParams({
    tenant: scope.tenant,
    namespace: scope.namespace,
  }).toString();
  const detail = useQuery({
    queryKey: ["automation-run", automationId, runId],
    queryFn: () => getAutomationRun(automationId, runId),
    refetchInterval: 2000,
  });
  if (detail.isLoading) return <WorkLoadingRows rows={3} />;
  if (!detail.data)
    return (
      <WorkEmpty
        title="Run could not be loaded"
        description={automationError(detail.error)}
      />
    );
  const { run, issue, tasks, artifacts } = detail.data;
  return (
    <div className="space-y-5 p-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap items-center gap-3">
          <RunBadge run={run} />
          <span className="text-sm text-slate-500">
            {run.source || run.triggerType} · {automationDate(run.createdAt)}
          </span>
        </div>
        {terminalRun(run.status) ? (
          <Button
            size="sm"
            variant="outline"
            onClick={() =>
              command(`rerun:${run.id}`, async (key) => {
                const next = await rerunAutomationRun(
                  automationId,
                  run.id,
                  key,
                );
                openRun(next.run.id);
              })
            }
          >
            Run again
          </Button>
        ) : (
          <Button
            size="sm"
            variant="outline"
            onClick={() =>
              command(`cancel:${run.id}`, () =>
                cancelAutomationRun(automationId, run.id),
              )
            }
          >
            Cancel run
          </Button>
        )}
      </div>
      <dl className="grid gap-3 rounded-xl bg-slate-50 p-4 text-sm sm:grid-cols-3">
        {[
          ["Scheduled", run.scheduledAt],
          ["Started", run.startedAt],
          ["Finished", run.completedAt],
        ].map(([label, value]) => (
          <div key={label}>
            <dt className="text-slate-500">{label}</dt>
            <dd>{automationDate(value)}</dd>
          </div>
        ))}
      </dl>
      {run.errorMessage && (
        <div
          role="alert"
          className="rounded-xl border border-red-100 bg-red-50 p-4 text-sm text-red-700"
        >
          {run.errorMessage}
          <span className="mt-1 block text-xs">{run.errorCode}</span>
        </div>
      )}
      {run.waitReason && (
        <p className="rounded-xl bg-amber-50 p-3 text-sm text-amber-900">
          {run.waitReason === "review"
            ? "Execution finished. Open the issue to review and accept the result."
            : `Waiting: ${run.waitReason}`}
        </p>
      )}
      {issue && (
        <Link
          className="text-sm text-blue-700 underline"
          to={`/work/issues/${issue.id}?${scopeQuery}`}
        >
          {issue.visibility === "operational"
            ? "Open execution record"
            : `Open issue: ${issue.title}`}
        </Link>
      )}
      {run.orchestrationRunId && (
        <Link
          className="block text-sm text-blue-700 underline"
          to={scope.scopedPath(`/work/executions/${run.orchestrationRunId}`)}
        >
          Open execution graph and sessions
        </Link>
      )}
      <section>
        <h3 className="mb-2 text-sm font-semibold">Result</h3>
        {run.output != null ? (
          <pre className="max-h-[480px] overflow-auto whitespace-pre-wrap break-words rounded-xl border border-slate-200 p-4 text-sm leading-6">
            {displayValue(run.output)}
          </pre>
        ) : (
          <p className="text-sm text-slate-500">
            {terminalRun(run.status)
              ? "No result was produced."
              : "The result will appear here when work is available."}
          </p>
        )}
      </section>
      {!!tasks?.length && (
        <section>
          <h3 className="mb-2 text-sm font-semibold">Tasks</h3>
          <div className="divide-y rounded-xl border border-slate-200">
            {tasks.map((task) => (
              <div
                className="flex flex-wrap justify-between gap-2 p-3 text-sm"
                key={task.id}
              >
                <Link
                  to={`/work/executions/tasks/${task.id}?${scopeQuery}`}
                  className="text-blue-700"
                >
                  <AgentIdentity agentId={task.agentId} showId={false} />
                </Link>
                <span>{task.status}</span>
                {task.errorMessage && (
                  <p className="w-full text-red-700">{task.errorMessage}</p>
                )}
              </div>
            ))}
          </div>
        </section>
      )}
      {!!artifacts?.length && (
        <section>
          <h3 className="mb-2 text-sm font-semibold">Artifacts</h3>
          {artifacts.map((artifact) => (
            <div className="text-sm" key={artifact.id}>
              {artifact.filename}
            </div>
          ))}
          {issue && (
            <Link
              className="text-sm text-blue-700 underline"
              to={`/work/issues/${issue.id}?${scopeQuery}`}
            >
              Open artifacts in the execution record
            </Link>
          )}
        </section>
      )}
      <details>
        <summary className="cursor-pointer text-sm font-medium">
          Input and configuration used for this run
        </summary>
        <div className="mt-3 space-y-3">
          <p className="text-xs text-slate-500">
            {run.snapshot
              ? `Configuration version ${run.snapshot.version}`
              : "This older run has no configuration snapshot."}
          </p>
          {run.snapshot?.execution?.runbook && (
            <pre className="whitespace-pre-wrap rounded-lg bg-slate-50 p-4 text-sm">
              {run.snapshot.execution.runbook}
            </pre>
          )}
          <pre className="max-h-64 overflow-auto whitespace-pre-wrap break-words rounded-lg bg-slate-50 p-4 text-xs">
            {displayValue(run.input ?? {})}
          </pre>
        </div>
      </details>
    </div>
  );
}
