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

import { useState } from "react";
import { Link } from "react-router-dom";
import {
  ArrowUpRight,
  Search,
  MessageSquare,
  ListChecks,
  Radio,
} from "lucide-react";
import { useControlPlaneScope } from "@/app/ScopeContext";
import {
  useEntityIdentities,
  getEntityIdentity,
  type EntityIdentityMap,
} from "@/components/EntityIdentity";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Input } from "@/components/ui/input";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogBody,
} from "@/components/ui/dialog";
import { EmptyState } from "@/components/EmptyState";
import { JsonViewer } from "@/components/JsonViewer";
import { PressureGauge } from "@/components/PressureGauge";
import { agentSessionDetailPath } from "@/features/operate/api";
import { formatRelative } from "@/lib/format";
import {
  activityTime,
  isWorking,
  needsAttention,
  resultSummary,
  sessionSource,
  sessionStatus,
  type AgentActivityRecord,
} from "./agentActivity";
import type { useAgentActivity } from "./useAgentActivity";

type ActivityData = ReturnType<typeof useAgentActivity>;
const selectClass =
  "h-10 min-w-0 rounded-lg border border-border bg-white px-3 text-sm";

function activityTitle(
  record: AgentActivityRecord,
  identities: EntityIdentityMap,
): string {
  if (record.task) {
    return (
      getEntityIdentity(identities, "issue", record.task.issueId)?.name ||
      `Task ${record.task.id.slice(0, 8)}`
    );
  }
  const session = record.sessions[0];
  const identity =
    session.originRef &&
    getEntityIdentity(identities, session.originType, session.originRef);
  return identity && identity.resolved
    ? identity.name
    : `${record.source} session · ${session.sessionId}`;
}
function tone(
  status: string,
): "success" | "danger" | "warning" | "info" | "default" {
  return status === "completed"
    ? "success"
    : needsAttention(status)
      ? "danger"
      : isWorking(status)
        ? "info"
        : "default";
}
function summary(record: AgentActivityRecord) {
  if (!record.task) return "Open messages and execution context";
  return (
    record.task.errorMessage ||
    resultSummary(record.task.result) ||
    (record.status === "waiting"
      ? "Waiting for the next step"
      : isWorking(record.status)
        ? "Work is in progress"
        : "Open the work record for details")
  );
}

export function ActivitySummary({
  data,
  onSelect,
}: {
  data: ActivityData;
  onSelect?: (status: string) => void;
}) {
  const Metric = onSelect ? "button" : "div";
  const available = data.tasks.isSuccess && data.sessions.isSuccess;
  const entries = [
    [
      "In progress",
      data.records.filter((item) => isWorking(item.status)).length,
      "working",
    ],
    [
      "Needs attention",
      data.records.filter((item) => needsAttention(item.status)).length,
      "attention",
    ],
    [
      "Completed · 24h",
      data.records.filter(
        (item) =>
          item.status === "completed" &&
          Date.now() - Date.parse(item.timestamp || "") < 86_400_000,
      ).length,
      "completed",
    ],
  ] as const;
  return (
    <div className="grid divide-y divide-border overflow-hidden rounded-xl border border-border bg-white sm:grid-cols-3 sm:divide-x sm:divide-y-0">
      {entries.map(([label, value, status]) => (
        <Metric
          key={label}
          onClick={onSelect ? () => onSelect(status) : undefined}
          className={`flex items-center justify-between gap-3 px-5 py-4 ${onSelect ? "text-left hover:bg-muted/30 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-primary" : ""}`}
        >
          <span className="text-sm text-muted-foreground">{label}</span>
          <span className="text-2xl font-semibold tabular-nums">
            {available ? value : "—"}
          </span>
        </Metric>
      ))}
    </div>
  );
}

export function AgentActivityPanel({
  agentId,
  data,
  view = "work",
  onViewChange,
  compact = false,
  onOpenActivity,
}: {
  agentId: string;
  data: ActivityData;
  view?: string;
  onViewChange?: (view: string) => void;
  compact?: boolean;
  onOpenActivity?: () => void;
}) {
  const scope = useControlPlaneScope();
  const [search, setSearch] = useState("");
  const [status, setStatus] = useState("all");
  const [source, setSource] = useState("all");
  const [period, setPeriod] = useState("all");
  const [page, setPage] = useState(0);
  const [selectedId, setSelectedId] = useState<string>();
  const selected = data.records.find((item) => item.id === selectedId);
  const identities = useEntityIdentities(
    data.records.map((item) =>
      item.task
        ? { type: "issue", ref: item.task.issueId }
        : {
            type: item.sessions[0]?.originType,
            ref: item.sessions[0]?.originRef,
          },
    ),
  );
  const query = search.trim().toLowerCase();
  const sources = [...new Set(data.records.map((item) => item.source))].sort();
  const matchStatus = (value: string) =>
    status === "all" ||
    (status === "working"
      ? isWorking(value)
      : status === "attention"
        ? needsAttention(value)
        : value === status);
  const matchTime = (timestamp?: string) =>
    period === "all" ||
    Date.now() - Date.parse(timestamp || "") < Number(period) * 86_400_000;
  const filtered = data.records.filter(
    (item) =>
      matchStatus(item.status) &&
      (source === "all" || item.source === source) &&
      matchTime(item.timestamp) &&
      `${activityTitle(item, identities)} ${summary(item)} ${item.task?.id || ""} ${item.sessions.map((s) => s.sessionId).join(" ")}`
        .toLowerCase()
        .includes(query),
  );
  const runtimeSessions = [...(data.sessions.data || [])]
    .sort(
      (a, b) =>
        (Date.parse(b.lastActiveAt || b.startedAt || "") || 0) -
        (Date.parse(a.lastActiveAt || a.startedAt || "") || 0),
    )
    .filter(
      (item) =>
        matchStatus(sessionStatus(item)) &&
        (source === "all" || sessionSource(item) === source) &&
        matchTime(item.lastActiveAt || item.startedAt) &&
        `${item.sessionId} ${item.id} ${item.originRef || ""} ${item.instanceRef || ""}`
          .toLowerCase()
          .includes(query),
    );
  const sessionsView = !compact && view === "sessions";
  const total = sessionsView ? runtimeSessions.length : filtered.length;
  const pageSize = compact ? 5 : 20;
  const currentPage = Math.min(
    page,
    Math.max(0, Math.ceil(total / pageSize) - 1),
  );
  const visible = filtered.slice(
    currentPage * pageSize,
    (currentPage + 1) * pageSize,
  );
  const visibleSessions = runtimeSessions.slice(
    currentPage * pageSize,
    (currentPage + 1) * pageSize,
  );
  const reset = (update: () => void) => {
    setPage(0);
    update();
  };
  const changeView = (next: string) => {
    setPage(0);
    setSearch("");
    setStatus("all");
    setSource("all");
    onViewChange?.(next);
  };
  const loading = data.tasks.isPending || data.sessions.isPending;

  return (
    <div className="space-y-4">
      {!compact && (
        <ActivitySummary
          data={data}
          onSelect={(value) => {
            setPage(0);
            setSearch("");
            setSource("all");
            setStatus(value);
            setPeriod(value === "completed" ? "1" : "all");
            onViewChange?.("work");
          }}
        />
      )}
      <section className="overflow-hidden rounded-xl border border-border bg-white">
        <div className="flex flex-wrap items-center justify-between gap-3 border-b border-border px-5 py-4">
          {compact ? (
            <h2 className="font-semibold">Recent work</h2>
          ) : (
            <div
              className="flex rounded-lg bg-muted p-1"
              aria-label="Activity view"
            >
              <Button
                size="sm"
                variant={sessionsView ? "ghost" : "secondary"}
                aria-pressed={!sessionsView}
                onClick={() => changeView("work")}
              >
                <ListChecks className="mr-2 h-4 w-4" />
                Work records
              </Button>
              <Button
                size="sm"
                variant={sessionsView ? "secondary" : "ghost"}
                aria-pressed={sessionsView}
                onClick={() => changeView("sessions")}
              >
                <Radio className="mr-2 h-4 w-4" />
                Runtime sessions
              </Button>
            </div>
          )}
          {compact ? (
            <Button size="sm" variant="ghost" onClick={onOpenActivity}>
              View all activity <ArrowUpRight className="ml-1 h-4 w-4" />
            </Button>
          ) : (
            <span className="text-xs text-muted-foreground">
              {data.tasks.isFetching || data.sessions.isFetching
                ? "Updating…"
                : `${total} ${sessionsView ? "sessions" : "work records"}`}
            </span>
          )}
        </div>
        {!compact && (
          <div className="flex flex-wrap gap-2 border-b border-border bg-muted/20 px-5 py-3">
            <div className="relative min-w-48 flex-1">
              <Search className="absolute left-3 top-3 h-4 w-4 text-muted-foreground" />
              <Input
                aria-label="Search activity"
                placeholder={
                  sessionsView
                    ? "Search session or instance…"
                    : "Search work, results, or ID…"
                }
                className="pl-9"
                value={search}
                onChange={(e) => reset(() => setSearch(e.target.value))}
              />
            </div>
            <select
              className={selectClass}
              aria-label="Activity status"
              value={status}
              onChange={(e) => reset(() => setStatus(e.target.value))}
            >
              <option value="all">All statuses</option>
              <option value="working">In progress</option>
              <option value="attention">Needs attention</option>
              <option value="waiting">Waiting</option>
              <option value="completed">Completed</option>
              <option value="cancelled">Cancelled</option>
              <option value="idle">Idle</option>
              <option value="active">Active session</option>
              <option value="archived">Archived</option>
              <option value="terminated">Terminated</option>
            </select>
            <select
              className={selectClass}
              aria-label="Activity source"
              value={source}
              onChange={(e) => reset(() => setSource(e.target.value))}
            >
              <option value="all">All sources</option>
              {[
                ...new Set(
                  sessionsView
                    ? (data.sessions.data || []).map(sessionSource)
                    : sources,
                ),
              ]
                .sort()
                .map((value) => (
                  <option key={value}>{value}</option>
                ))}
            </select>
            <select
              className={selectClass}
              aria-label="Activity time range"
              value={period}
              onChange={(e) => reset(() => setPeriod(e.target.value))}
            >
              <option value="all">All time</option>
              <option value="1">Last 24 hours</option>
              <option value="7">Last 7 days</option>
              <option value="30">Last 30 days</option>
            </select>
          </div>
        )}
        {(data.tasks.isError || data.sessions.isError) && (
          <div
            role="alert"
            className="border-b border-border bg-amber-50 px-5 py-3 text-sm text-amber-800"
          >
            Some activity could not be loaded.{" "}
            {data.tasks.isError && "Work records unavailable. "}
            {data.sessions.isError && "Runtime sessions unavailable. "}
            <button
              className="underline"
              onClick={() => {
                void data.tasks.refetch();
                void data.sessions.refetch();
              }}
            >
              Retry
            </button>
          </div>
        )}
        {loading && !total ? (
          <p className="p-8 text-sm text-muted-foreground" role="status">
            Loading activity…
          </p>
        ) : sessionsView ? (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="bg-muted/20 text-muted-foreground">
                <tr>
                  <th className="px-5 py-3 font-medium">
                    Session / related work
                  </th>
                  <th className="px-4 py-3 font-medium">State</th>
                  <th className="px-4 py-3 font-medium">Context</th>
                  <th className="px-5 py-3 font-medium">Last active</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {visibleSessions.map((session) => {
                  const related = data.records.filter(
                    (item) =>
                      item.task &&
                      item.sessions.some((s) => s.id === session.id),
                  );
                  return (
                    <tr key={session.id} className="hover:bg-muted/20">
                      <td className="max-w-md px-5 py-4">
                        <Link
                          className="font-medium text-primary hover:underline"
                          to={scope.scopedPath(
                            agentSessionDetailPath(agentId, session),
                          )}
                        >
                          {session.sessionId}
                        </Link>
                        <div className="mt-1 text-xs text-muted-foreground">
                          {sessionSource(session)}
                          {session.instanceRef
                            ? ` · ${session.instanceRef}`
                            : ""}
                        </div>
                        {related.map((record) => (
                          <button
                            key={record.id}
                            className="mt-1 block max-w-full truncate text-sm text-muted-foreground hover:text-primary"
                            onClick={() => setSelectedId(record.id)}
                          >
                            {activityTitle(record, identities)}
                          </button>
                        ))}
                      </td>
                      <td className="px-4 py-4">
                        <Badge tone={tone(sessionStatus(session))}>
                          {sessionStatus(session)}
                        </Badge>
                      </td>
                      <td className="px-4 py-4">
                        <PressureGauge
                          value={session.snapshot?.contextPressure}
                        />
                      </td>
                      <td className="whitespace-nowrap px-5 py-4 text-muted-foreground">
                        {formatRelative(
                          session.lastActiveAt || session.startedAt,
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        ) : (
          <div className="divide-y divide-border">
            {visible.map((record) => (
              <button
                key={record.id}
                aria-label={`Open ${activityTitle(record, identities)} · ${record.status}`}
                className="group flex w-full items-start gap-4 px-5 py-4 text-left transition-colors hover:bg-muted/30 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-primary"
                onClick={() => setSelectedId(record.id)}
              >
                <span
                  className={`mt-1 rounded-lg p-2 ${needsAttention(record.status) ? "bg-red-50 text-red-600" : isWorking(record.status) ? "bg-indigo-50 text-indigo-600" : "bg-muted text-muted-foreground"}`}
                >
                  {record.task ? (
                    <ListChecks className="h-4 w-4" />
                  ) : (
                    <MessageSquare className="h-4 w-4" />
                  )}
                </span>
                <span className="min-w-0 flex-1">
                  <span className="flex flex-wrap items-center gap-2">
                    <span className="font-medium">
                      {activityTitle(record, identities)}
                    </span>
                    <Badge tone={tone(record.status)}>{record.status}</Badge>
                  </span>
                  <span className="mt-1 block truncate text-sm text-muted-foreground">
                    {summary(record)}
                  </span>
                  <span className="mt-2 block text-xs text-muted-foreground">
                    {record.source}
                    {record.sessions.length > 0
                      ? ` · ${record.sessions.length} ${record.sessions.length === 1 ? "session" : "sessions"}`
                      : ""}
                    {(record.attempts?.length || 0) > 1
                      ? ` · ${record.attempts!.length - 1} retries`
                      : record.task?.triggerType === "retry"
                        ? " · Retried work"
                        : ""}
                  </span>
                </span>
                <span className="flex shrink-0 items-center gap-2 text-xs text-muted-foreground">
                  <span>{formatRelative(record.timestamp)}</span>
                  <ArrowUpRight className="h-4 w-4" />
                </span>
              </button>
            ))}
          </div>
        )}
        {!loading && total === 0 && (
          <EmptyState
            title={
              search || status !== "all" || source !== "all" || period !== "all"
                ? "No matching activity"
                : "No activity yet"
            }
            description="Work records appear when this Agent handles a request. Idle runtime sessions remain available in the session view."
            className="py-12"
          />
        )}
        {!compact && total > 0 && (
          <div className="flex items-center justify-between border-t border-border px-5 py-3 text-sm text-muted-foreground">
            <span>
              {currentPage * pageSize + 1}–
              {Math.min((currentPage + 1) * pageSize, total)} of {total}
            </span>
            <div className="flex gap-2">
              <Button
                size="sm"
                variant="outline"
                disabled={currentPage === 0}
                onClick={() => setPage(currentPage - 1)}
              >
                Previous
              </Button>
              <Button
                size="sm"
                variant="outline"
                disabled={(currentPage + 1) * pageSize >= total}
                onClick={() => setPage(currentPage + 1)}
              >
                Next
              </Button>
            </div>
          </div>
        )}
      </section>
      <Dialog
        open={!!selected}
        onOpenChange={(open) => {
          if (!open) setSelectedId(undefined);
        }}
      >
        {selected && (
          <DialogContent
            size="lg"
            className="left-auto right-0 top-0 h-dvh max-h-dvh w-full max-w-2xl translate-x-0 translate-y-0 rounded-none"
          >
            <DialogHeader>
              <DialogTitle>{activityTitle(selected, identities)}</DialogTitle>
              <DialogDescription>
                {selected.source} · {selected.status} ·{" "}
                {formatRelative(selected.timestamp)}
              </DialogDescription>
            </DialogHeader>
            <DialogBody className="space-y-6">
              <section>
                <h3 className="mb-2 text-sm font-semibold">
                  Result & progress
                </h3>
                <p className="whitespace-pre-wrap break-words text-sm leading-6">
                  {summary(selected)}
                </p>
                {selected.task?.result != null &&
                  !resultSummary(selected.task.result) && (
                    <JsonViewer
                      value={selected.task.result}
                      className="mt-3 max-h-64"
                    />
                  )}
              </section>
              {selected.task && (
                <>
                  <section>
                    <h3 className="mb-3 text-sm font-semibold">
                      {(selected.attempts?.length || 0) > 1
                        ? "Latest attempt timeline"
                        : "Execution timeline"}
                    </h3>
                    <ol className="space-y-3 border-l-2 border-border pl-4 text-sm">
                      {[
                        ["Received", selected.task.createdAt],
                        ["Dispatched", selected.task.dispatchedAt],
                        ["Started", selected.task.startedAt],
                        [
                          selected.status === "completed"
                            ? "Completed"
                            : "Finished",
                          selected.task.completedAt,
                        ],
                      ]
                        .filter(([, time]) => time)
                        .map(([label, time]) => (
                          <li
                            key={label}
                            className="flex justify-between gap-3"
                          >
                            <span>{label}</span>
                            <time className="text-muted-foreground">
                              {new Date(time!).toLocaleString()}
                            </time>
                          </li>
                        ))}
                    </ol>
                  </section>
                  <div className="flex flex-wrap gap-3 text-sm">
                    <Link
                      className="text-primary hover:underline"
                      to={scope.scopedPath(
                        `/work/executions/tasks/${selected.task.id}`,
                      )}
                    >
                      Open task & execution attempts ↗
                    </Link>
                    {selected.task.issueId && (
                      <Link
                        className="text-primary hover:underline"
                        to={scope.scopedPath(
                          `/work/issues/${selected.task.issueId}`,
                        )}
                      >
                        Open source Issue ↗
                      </Link>
                    )}
                  </div>
                </>
              )}
              {(selected.attempts?.length || 0) > 1 && (
                <section>
                  <h3 className="mb-3 text-sm font-semibold">
                    Execution attempts
                  </h3>
                  <div className="space-y-2">
                    {selected.attempts!.map((attempt, index) => (
                      <Link
                        key={attempt.id}
                        to={scope.scopedPath(
                          `/work/executions/tasks/${attempt.id}`,
                        )}
                        className="block rounded-lg border border-border p-3 text-sm hover:bg-muted/30"
                      >
                        <span className="flex items-center justify-between gap-2">
                          <span>
                            {index === 0
                              ? "Original attempt"
                              : `Retry ${index}`}
                          </span>
                          <Badge tone={tone(attempt.status)}>
                            {attempt.status}
                          </Badge>
                        </span>
                        <span className="mt-1 block truncate text-xs text-muted-foreground">
                          {attempt.errorMessage ||
                            resultSummary(attempt.result) ||
                            formatRelative(activityTime(attempt))}
                        </span>
                      </Link>
                    ))}
                  </div>
                </section>
              )}
              <section>
                <h3 className="mb-3 text-sm font-semibold">Runtime context</h3>
                <div className="space-y-2">
                  {selected.sessions.map((session) => (
                    <Link
                      key={session.id}
                      className="flex items-center justify-between gap-3 rounded-lg border border-border p-3 text-sm hover:bg-muted/30"
                      to={scope.scopedPath(
                        agentSessionDetailPath(agentId, session),
                      )}
                    >
                      <span className="min-w-0">
                        <span className="block truncate font-medium">
                          {session.sessionId}
                        </span>
                        <span className="text-xs text-muted-foreground">
                          Messages, events, context and diagnostics
                        </span>
                      </span>
                      <Badge>{sessionStatus(session)}</Badge>
                    </Link>
                  ))}
                  {!selected.sessions.length && (
                    <p className="text-sm text-muted-foreground">
                      No linked runtime session is available.
                    </p>
                  )}
                </div>
              </section>
              <details className="text-sm">
                <summary className="cursor-pointer text-muted-foreground">
                  Record identifiers
                </summary>
                <pre className="mt-2 whitespace-pre-wrap break-all rounded-lg bg-muted p-3 text-xs">
                  {selected.task
                    ? `Task: ${selected.task.id}\nLatest activity: ${activityTime(selected.task)}`
                    : `Session: ${selected.sessions[0]?.id}`}
                </pre>
              </details>
            </DialogBody>
          </DialogContent>
        )}
      </Dialog>
    </div>
  );
}
