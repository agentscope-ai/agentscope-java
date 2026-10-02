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

import ReactMarkdown from "react-markdown";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { ArrowDown, ChevronRight, GitBranch, Users } from "lucide-react";
import { useControlPlaneScope } from "@/app/ScopeContext";
import { getRunGraph } from "@/api/orchestration";
import { AgentIdentity } from "@/components/AgentPicker";
import { EmptyState } from "@/components/EmptyState";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  CardDescription,
} from "@/components/ui/card";
import {
  Dialog,
  DialogBody,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { formatRelative } from "@/lib/format";
import { TeamTaskMap } from "./TeamTaskMap";
import { resultSummary } from "@/features/build/agents/agentActivity";
import {
  buildTaskSteps,
  stateLabel,
  teamTone,
  terminalRun,
  matchesActivity,
  type ActivityFilter,
  type TeamActivity,
} from "./teamActivity";

export function TeamActivityPanel({
  records,
  loading,
  error,
  warning,
  compact = false,
  filter = "all",
  onFilter,
}: {
  records: TeamActivity[];
  loading: boolean;
  error?: unknown;
  warning?: string;
  compact?: boolean;
  filter?: ActivityFilter;
  onFilter?: (value: ActivityFilter) => void;
}) {
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<string>();
  const filtered = records.filter(
    (record) =>
      matchesActivity(record, filter) &&
      `${record.title} ${record.summary} ${record.id} ${record.tasks.map((t) => t.teamRole).join(" ")}`
        .toLowerCase()
        .includes(search.toLowerCase()),
  );
  const pageSize = compact ? 5 : 12;
  const safePage = Math.min(
    page,
    Math.max(0, Math.ceil(filtered.length / pageSize) - 1),
  );
  const ordered = compact
    ? [...filtered].sort(
        (a, b) =>
          Number(matchesActivity(b, "active")) -
          Number(matchesActivity(a, "active")),
      )
    : filtered;
  const visible = ordered.slice(safePage * pageSize, (safePage + 1) * pageSize);
  const activeRecord = records.find((record) => record.id === selected);
  return (
    <>
      <Card>
        <CardHeader>
          <CardTitle>
            {compact ? "Current & recent collaborations" : "Collaborations"}
          </CardTitle>
          <CardDescription>
            One record per execution Run, including its Lead and worker tasks.
          </CardDescription>
        </CardHeader>
        <CardContent>
          {!compact && (
            <div className="mb-4 flex flex-wrap gap-3">
              <Input
                className="min-w-48 flex-1"
                aria-label="Search collaborations"
                placeholder="Search goals, results or roles…"
                value={search}
                onChange={(e) => {
                  setSearch(e.target.value);
                  setPage(0);
                }}
              />
              <select
                aria-label="Collaboration status"
                className="rounded-md border border-border bg-background px-3 py-2 text-sm"
                value={filter}
                onChange={(e) => {
                  onFilter?.(e.target.value as ActivityFilter);
                  setPage(0);
                }}
              >
                <option value="all">All collaborations</option>
                <option value="active">In progress</option>
                <option value="attention">Needs attention</option>
                <option value="completed">Succeeded in last 24 hours</option>
              </select>
            </div>
          )}
          {!!error && (
            <p role="alert" className="mb-3 text-sm text-destructive">
              Activity could not refresh: {String(error)}
            </p>
          )}
          {warning && (
            <p
              role="status"
              className="mb-3 rounded-md bg-amber-50 p-3 text-sm text-amber-800"
            >
              {warning}
            </p>
          )}
          {loading && !records.length ? (
            <p className="py-8 text-sm text-muted-foreground">
              Loading collaboration history…
            </p>
          ) : (
            <div className="divide-y divide-border">
              {visible.map((record) => (
                <button
                  key={record.id}
                  onClick={() => setSelected(record.id)}
                  className="group flex w-full items-start gap-4 rounded-md px-2 py-4 text-left hover:bg-muted/50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                >
                  <span className="mt-1 rounded-lg bg-muted p-2">
                    <GitBranch className="h-4 w-4" />
                  </span>
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="line-clamp-2 font-medium">
                        {record.title}
                      </span>
                      <Badge tone={teamTone(record.state)}>
                        {stateLabel(record.state)}
                      </Badge>
                    </div>
                    <p className="mt-1 line-clamp-2 text-sm text-muted-foreground">
                      {record.summary.replace(/[#*`]/g, "").slice(0, 240)}
                      {record.summary.length > 240 ? "…" : ""}
                    </p>
                    <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-muted-foreground">
                      <span className="inline-flex items-center gap-1">
                        <Users className="h-3 w-3" />
                        {new Set(record.tasks.map((t) => t.agentId)).size}{" "}
                        participants
                      </span>
                      <span>
                        {buildTaskSteps(record.tasks).length} work steps ·{" "}
                        {record.tasks.length} task attempts
                      </span>
                      <span>{formatRelative(record.updatedAt)}</span>
                      {record.run?.parentRunId && (
                        <span>Child collaboration</span>
                      )}
                    </div>
                  </div>
                  <ChevronRight className="mt-2 h-4 w-4 shrink-0 text-muted-foreground" />
                </button>
              ))}
            </div>
          )}
          {!loading && !error && !visible.length && (
            <EmptyState
              title={
                records.length
                  ? "No matching collaborations"
                  : "No collaboration history"
              }
              description={
                records.length
                  ? "Try another search or status filter."
                  : "Assign an Issue or invoke a published Team API to start work."
              }
            />
          )}
          {!compact && filtered.length > pageSize && (
            <div className="mt-4 flex items-center justify-between border-t border-border pt-4 text-sm">
              <span>
                {safePage * pageSize + 1}–
                {Math.min((safePage + 1) * pageSize, filtered.length)} of{" "}
                {filtered.length}
              </span>
              <div className="flex gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={!safePage}
                  onClick={() => setPage(safePage - 1)}
                >
                  Previous
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={(safePage + 1) * pageSize >= filtered.length}
                  onClick={() => setPage(safePage + 1)}
                >
                  Next
                </Button>
              </div>
            </div>
          )}
        </CardContent>
      </Card>
      <Dialog
        open={!!activeRecord}
        onOpenChange={(open) => {
          if (!open) setSelected(undefined);
        }}
      >
        <DialogContent size="xl">
          <DialogHeader>
            <DialogTitle>{activeRecord?.title || "Collaboration"}</DialogTitle>
            <DialogDescription>
              Recorded participation and task relationships for this
              collaboration.
            </DialogDescription>
          </DialogHeader>
          <DialogBody>
            {activeRecord && (
              <CollaborationDetail
                key={activeRecord.id}
                record={activeRecord}
              />
            )}
          </DialogBody>
        </DialogContent>
      </Dialog>
    </>
  );
}
function CollaborationDetail({ record }: { record: TeamActivity }) {
  const scope = useControlPlaneScope();
  const steps = buildTaskSteps(record.tasks);
  const [selected, setSelected] = useState(
    steps.find((step) =>
      ["running", "waiting", "dispatched", "queued"].includes(
        step.latest.status,
      ),
    )?.id ||
      steps.find((step) => step.latest.status === "failed")?.id ||
      steps[steps.length - 1]?.id,
  );
  const step = steps.find((item) => item.id === selected);
  const graph = useQuery({
    queryKey: ["team-run-graph", scope.tenant, scope.namespace, record.id],
    queryFn: () => getRunGraph(record.id),
    enabled: !!record.run,
    refetchInterval: terminalRun(record.state) ? false : 15_000,
    refetchIntervalInBackground: false,
  });
  // Edges are rendered only from explicit task references. Time order alone is
  // never presented as delegation, and current roster changes cannot rewrite it.
  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center gap-3">
        <Badge tone={teamTone(record.state)}>{stateLabel(record.state)}</Badge>
        {record.issueId && (
          <Link
            className="text-sm text-primary hover:underline"
            to={scope.scopedPath(`/work/issues/${record.issueId}`)}
          >
            Source Issue ↗
          </Link>
        )}
        {record.run && (
          <Link
            className="text-sm text-primary hover:underline"
            to={scope.scopedPath(`/work/executions/${record.id}`)}
          >
            Execution details ↗
          </Link>
        )}
        {record.run?.parentRunId && (
          <Link
            className="text-sm text-primary hover:underline"
            to={scope.scopedPath(`/work/executions/${record.run.parentRunId}`)}
          >
            Parent collaboration ↗
          </Link>
        )}
      </div>
      <section className="rounded-xl border border-border bg-muted/20 p-4">
        <h3 className="text-sm font-semibold">
          {record.run?.failureMessage
            ? "What needs attention"
            : "Result & current state"}
        </h3>
        <div className="md-text mt-2 max-h-56 overflow-auto break-words text-sm leading-6">
          <ReactMarkdown>{record.summary}</ReactMarkdown>
        </div>
        {!!resultSummary(record.run?.input) && (
          <details className="mt-3 text-sm">
            <summary className="cursor-pointer text-muted-foreground">
              Original request
            </summary>
            <p className="mt-2 whitespace-pre-wrap break-words">
              {resultSummary(record.run?.input)}
            </p>
          </details>
        )}
      </section>
      <div>
        <h3 className="font-semibold">Collaboration map</h3>
        <p className="mt-1 text-xs text-muted-foreground">
          Each card is a work step; retries are grouped. Arrows show recorded
          delegation or parent task relationships.
        </p>
      </div>
      <div className="grid items-start gap-4 lg:grid-cols-[1.25fr_1fr]">
        <TeamTaskMap steps={steps} selected={selected} onSelect={setSelected} />
        <div className="min-w-0 rounded-xl border border-border p-4">
          {step && (
            <>
              <div className="flex items-center gap-2">
                <h4 className="font-semibold">
                  Step {steps.indexOf(step) + 1}
                </h4>
                <Badge>
                  {step.latest.leaderTask
                    ? "Lead"
                    : step.latest.teamRole || "Worker"}
                </Badge>
              </div>
              <div className="mt-2 text-sm">
                <AgentIdentity agentId={step.latest.agentId} />
              </div>
              {step.latest.taskTitle && (
                <p className="mt-3 text-sm font-medium">
                  {step.latest.taskTitle}
                </p>
              )}
              <Link
                className="mt-1 block text-xs text-primary hover:underline"
                to={scope.scopedPath(`/work/issues/${step.latest.issueId}`)}
              >
                Task Issue ↗
              </Link>
              <p className="mt-3 whitespace-pre-wrap break-words text-sm">
                {step.latest.errorMessage ||
                  resultSummary(step.latest.result) ||
                  step.latest.waitReason ||
                  "No result recorded yet."}
              </p>
              <div className="mt-4 space-y-3 border-t border-border pt-3">
                {step.tasks.map((task, index) => (
                  <div key={task.id} className="text-xs">
                    <div className="flex items-center justify-between gap-2">
                      <Link
                        className="text-primary hover:underline"
                        to={scope.scopedPath(
                          `/work/executions/tasks/${task.id}`,
                        )}
                      >
                        {index ? `Retry ${index}` : "Initial task"} ↗
                      </Link>
                      <Badge tone={teamTone(task.status)}>
                        {stateLabel(task.status)}
                      </Badge>
                    </div>
                    <p className="mt-1 text-muted-foreground">
                      Created {formatRelative(task.createdAt)}
                      {task.completedAt
                        ? ` · Ended ${formatRelative(task.completedAt)}`
                        : ""}
                    </p>
                    {(graph.data?.attempts || [])
                      .filter(
                        (attempt) =>
                          attempt.agentTaskId === task.id && attempt.sessionRef,
                      )
                      .map((attempt) => (
                        <Link
                          key={attempt.id}
                          className="mt-1 block text-primary hover:underline"
                          to={scope.scopedPath(
                            `/work/sessions/${encodeURIComponent(attempt.sessionRef!)}`,
                          )}
                        >
                          Runtime session · attempt {attempt.attempt} ↗
                        </Link>
                      ))}
                  </div>
                ))}
              </div>
            </>
          )}
          {graph.isError && (
            <p className="mt-3 text-xs text-destructive">
              Runtime session links could not be loaded.
            </p>
          )}
        </div>
      </div>
      <details className="rounded-lg border border-border p-4">
        <summary className="cursor-pointer text-sm font-medium">
          Task timeline · {record.tasks.length} attempts
        </summary>
        <div className="mt-3 space-y-3">
          {record.tasks.map((task) => (
            <div key={task.id} className="flex items-start gap-3 text-xs">
              <ArrowDown className="mt-1 h-3 w-3 shrink-0 text-muted-foreground" />
              <div>
                <span className="text-muted-foreground">
                  {new Date(task.createdAt).toLocaleString()}
                </span>
                <p className="mt-1">
                  <AgentIdentity agentId={task.agentId} showId={false} /> ·{" "}
                  {task.teamRole || "Member"} · {stateLabel(task.status)}
                </p>
                {task.errorMessage && (
                  <p className="mt-1 text-destructive">{task.errorMessage}</p>
                )}
              </div>
            </div>
          ))}
        </div>
      </details>
    </div>
  );
}
