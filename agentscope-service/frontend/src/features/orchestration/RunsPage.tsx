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

import { WorkflowExecution } from "./WorkflowExecution";
import { namespaceCan } from "@/lib/namespaceScope";
import { allowedRunControls } from "./model";
import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useParams } from "react-router-dom";
import {
  getRunGraph,
  listRunEvents,
  listRuns,
  mutateRun,
  rerunRun,
  signalRun,
} from "@/api/orchestration";
import { useControlPlaneScope } from "@/app/ScopeContext";
import {
  EntityIdentityText,
  entityDisplayName,
  useEntityIdentities,
} from "@/components/EntityIdentity";
import { EmptyState } from "@/components/EmptyState";
import { Page, PageHeader } from "@/components/Page";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { formatRelative } from "@/lib/format";

const terminal = new Set([
  "succeeded",
  "partial_succeeded",
  "failed",
  "cancelled",
]);

export default function RunsPage() {
  const { runId } = useParams();
  return runId ? <RunDetail id={runId} /> : <RunList />;
}

function tone(state: string) {
  return state === "succeeded"
    ? "success"
    : state === "failed" || state === "cancelled"
      ? "danger"
      : "warning";
}

function RunList() {
  const scope = useControlPlaneScope();
  const runs = useQuery({
    queryKey: ["orchestration-runs", scope.tenant, scope.namespace],
    queryFn: () => listRuns(scope.tenant, scope.namespace),
    refetchInterval: 5000,
    refetchIntervalInBackground: false,
  });
  const identities = useEntityIdentities(
    (runs.data?.runs || []).map((run) => ({
      type: "issue",
      ref: run.rootIssueId,
    })),
  );
  return (
    <Page>
      <PageHeader
        title="Orchestration Runs"
        description="Direct, adaptive, declared, and subrun execution across Managed, External Application, and Hosted Runtime backends."
      />
      {!runs.isLoading && !runs.data?.runs.length ? (
        <EmptyState
          title="No runs"
          description="Assign an Issue or start a published Definition."
        />
      ) : (
        <div className="overflow-hidden rounded-xl border bg-white">
          <table className="w-full text-left text-sm">
            <thead className="border-b bg-muted/40">
              <tr>
                <th className="p-4">Run</th>
                <th>Mode</th>
                <th>Issue</th>
                <th>State</th>
                <th>Created</th>
              </tr>
            </thead>
            <tbody className="divide-y">
              {runs.data?.runs.map((run) => (
                <tr key={run.id}>
                  <td className="p-4">
                    <Link
                      className="font-mono text-primary"
                      to={scope.scopedPath(`/orchestration/runs/${run.id}`)}
                    >
                      {run.id.slice(0, 10)}
                    </Link>
                  </td>
                  <td>{run.mode}</td>
                  <td>
                    <Link
                      to={scope.scopedPath(`/work/issues/${run.rootIssueId}`)}
                    >
                      <EntityIdentityText
                        identities={identities}
                        type="issue"
                        entityRef={run.rootIssueId}
                        secondary
                      />
                    </Link>
                  </td>
                  <td>
                    <Badge tone={tone(run.state)}>{run.state}</Badge>
                  </td>
                  <td>{formatRelative(run.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Page>
  );
}

function RunDetail({ id }: { id: string }) {
  const scope = useControlPlaneScope();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const canEdit = namespaceCan(scope.roles, "write");
  const [signal, setSignal] = useState("");
  const graph = useQuery({
    queryKey: ["run-graph", id],
    queryFn: () => getRunGraph(id),
    refetchInterval: (query) =>
      terminal.has(query.state.data?.run.state ?? "") ? false : 2000,
    refetchIntervalInBackground: false,
  });
  const events = useQuery({
    queryKey: ["run-events", id],
    queryFn: () => listRunEvents(id),
    refetchInterval: () =>
      terminal.has(graph.data?.run.state ?? "") ? false : 2000,
    refetchIntervalInBackground: false,
  });
  const identities = useEntityIdentities([
    { type: "issue", ref: graph.data?.run.rootIssueId },
    ...(graph.data?.tasks || []).map((task) => ({
      type: "agent",
      ref: task.agentId,
    })),
    ...(graph.data?.tasks || []).map((task) => ({
      type: "issue",
      ref: String(task.issueId || ""),
    })),
    ...(graph.data?.attempts || []).map((attempt) => ({
      type: "runtime_host",
      ref: attempt.hostId,
    })),
    ...(events.data?.events || []).map((event) => ({
      type: event.actor.type,
      ref: event.actor.ref,
    })),
  ]);
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ["run-graph", id] });
    void queryClient.invalidateQueries({ queryKey: ["run-events", id] });
  };
  const mutate = useMutation({
    mutationFn: (action: "pause" | "resume" | "cancel") =>
      mutateRun(id, action),
    onSuccess: refresh,
  });
  const rerun = useMutation({
    mutationFn: () => rerunRun(id),
    onSuccess: ({ run }) =>
      navigate(scope.scopedPath(`/work/executions/${run.id}`)),
  });
  const send = useMutation({
    mutationFn: () => signalRun(id, signal, {}),
    onSuccess: () => {
      setSignal("");
      refresh();
    },
  });
  if (!graph.data)
    return (
      <Page>
        {graph.error ? (
          <>
            <p role="alert">{String(graph.error)}</p>
            <Button onClick={() => graph.refetch()}>Retry</Button>
          </>
        ) : (
          "Loading execution…"
        )}
      </Page>
    );
  const { run, nodes, edges, tasks, attempts } = graph.data;
  const toolFailures = (events.data?.events || []).filter(
    (event) => event.type === "agent_tool.failed",
  );
  const firstToolFailure = toolFailures[0]?.payload as
    Record<string, unknown> | undefined;
  const failureSessionRef = firstToolFailure?.sessionRef
    ? String(firstToolFailure.sessionRef)
    : "";
  const queuedTaskIds = new Set(
    tasks.filter((task) => task.status === "queued").map((task) => task.id),
  );
  const dispatchDeferrals = (events.data?.events || []).filter(
    (event) =>
      event.type === "agent-task.dispatch_deferred" &&
      !!event.agentTaskId &&
      queuedTaskIds.has(event.agentTaskId),
  );
  const firstDispatchDeferral = dispatchDeferrals[0]?.payload as
    Record<string, unknown> | undefined;
  return (
    <Page className="max-w-[1500px]">
      <Link
        to={scope.scopedPath(`/work/issues/${run.rootIssueId}`)}
        className="text-sm text-muted-foreground"
      >
        ← Issue
      </Link>
      <PageHeader
        title={
          <span className="flex gap-3">
            Execution {run.id.slice(0, 10)}{" "}
            <Badge tone={tone(run.state)}>{run.state}</Badge>
          </span>
        }
        description={
          <span className="break-words">
            {run.mode} run ·{" "}
            {entityDisplayName(identities, "issue", run.rootIssueId)}
          </span>
        }
        actions={
          <div className="flex gap-2">
            {canEdit && allowedRunControls(run.state).includes("pause") && (
              <Button
                variant="outline"
                disabled={mutate.isPending}
                onClick={() => mutate.mutate("pause")}
              >
                Pause
              </Button>
            )}
            {canEdit && run.state === "paused" && (
              <Button
                disabled={mutate.isPending}
                onClick={() => mutate.mutate("resume")}
              >
                Resume
              </Button>
            )}
            {canEdit && allowedRunControls(run.state).includes("cancel") && (
              <Button
                variant="destructive"
                disabled={mutate.isPending}
                onClick={() => mutate.mutate("cancel")}
              >
                Cancel
              </Button>
            )}
            {canEdit && terminal.has(run.state) && run.definitionRevisionId && (
              <Button onClick={() => rerun.mutate()} disabled={rerun.isPending}>
                Rerun
              </Button>
            )}
          </div>
        }
      />
      {(mutate.error ||
        rerun.error ||
        send.error ||
        graph.error ||
        events.error) && (
        <p role="alert" className="text-sm text-destructive">
          {String(
            mutate.error ||
              rerun.error ||
              send.error ||
              graph.error ||
              events.error,
          )}
        </p>
      )}
      <WorkflowExecution graph={graph.data} canEdit={canEdit} />
      <details>
        <summary className="cursor-pointer text-sm font-medium">
          Execution diagnostics · tasks, attempts and events
        </summary>
        {toolFailures.length > 0 && (
          <div className="mb-5 rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-800">
            <div className="flex items-center justify-between gap-3">
              <div className="font-semibold">
                {toolFailures.length} tool failure
                {toolFailures.length === 1 ? "" : "s"} recorded
              </div>
              {failureSessionRef && (
                <Link
                  className="shrink-0 font-medium underline"
                  to={scope.scopedPath(`/work/sessions/${failureSessionRef}`)}
                >
                  Open agent session
                </Link>
              )}
            </div>
            <div className="mt-1 break-words text-xs">
              First failure: {String(firstToolFailure?.toolName || "tool")} —{" "}
              {String(firstToolFailure?.message || "No error message")}
            </div>
          </div>
        )}
        {dispatchDeferrals.length > 0 && (
          <div className="mb-5 rounded-xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-900">
            <div className="font-semibold">
              {dispatchDeferrals.length} Agent task
              {dispatchDeferrals.length === 1 ? "" : "s"} waiting for runtime
              dispatch
            </div>
            <div className="mt-1 break-words text-xs">
              {String(
                firstDispatchDeferral?.message ||
                  "Runtime dispatch is temporarily unavailable. Automatic retry remains active.",
              )}
            </div>
          </div>
        )}
        <div className="grid gap-5 xl:grid-cols-[2fr_1fr]">
          <section className="space-y-5">
            {run.input != null && (
              <div className="rounded-xl border bg-white p-5">
                <h2 className="font-semibold">Original request input</h2>
                <pre className="mt-3 whitespace-pre-wrap break-words rounded bg-muted p-3 text-sm">
                  {typeof run.input === "string"
                    ? run.input
                    : JSON.stringify(run.input, null, 2)}
                </pre>
              </div>
            )}
            <div className="rounded-xl border bg-white p-5">
              <h2 className="font-semibold">Execution graph</h2>
              <div className="mt-4 grid gap-3 md:grid-cols-2">
                {nodes.map((node) => (
                  <article key={node.id} className="rounded-lg border p-3">
                    <div className="flex justify-between">
                      <strong>{node.nodeKey}</strong>
                      <Badge tone={tone(node.state)}>{node.state}</Badge>
                    </div>
                    <p className="text-xs text-muted-foreground">
                      {node.type}
                      {node.role ? ` · ${node.role}` : ""}
                    </p>
                    <div className="mt-2 text-xs">
                      in{" "}
                      {edges.filter((edge) => edge.toNodeId === node.id).length}{" "}
                      · out{" "}
                      {
                        edges.filter((edge) => edge.fromNodeId === node.id)
                          .length
                      }
                    </div>
                    {node.failureMessage && (
                      <p className="mt-2 text-xs text-red-600">
                        {node.failureCode}: {node.failureMessage}
                      </p>
                    )}
                  </article>
                ))}
              </div>
            </div>
            <div className="rounded-xl border bg-white p-5">
              <h2 className="font-semibold">
                Agent steps and runtime attempts
              </h2>
              <div className="mt-3 space-y-3">
                {tasks.map((task) => (
                  <div
                    key={task.id}
                    className="rounded-lg bg-muted p-3 text-sm"
                  >
                    <Link
                      className="font-mono text-primary"
                      to={scope.scopedPath(`/work/executions/tasks/${task.id}`)}
                    >
                      {task.id.slice(0, 8)}
                    </Link>{" "}
                    ·{" "}
                    <EntityIdentityText
                      identities={identities}
                      type="agent"
                      entityRef={task.agentId}
                      secondary
                    />{" "}
                    · {task.status}
                    <div className="mt-1 text-xs">
                      <Link
                        className="text-primary"
                        to={scope.scopedPath(
                          `/work/issues/${String(task.issueId)}`,
                        )}
                      >
                        <EntityIdentityText
                          identities={identities}
                          type="issue"
                          entityRef={String(task.issueId)}
                        />
                      </Link>{" "}
                      · {task.leaderTask ? "Lead decision" : "Worker task"}
                    </div>
                    <div className="mt-2 space-y-2">
                      {attempts
                        .filter((attempt) => attempt.agentTaskId === task.id)
                        .map((attempt) => (
                          <div
                            key={attempt.id}
                            className="rounded border bg-background p-2"
                          >
                            <div className="flex flex-wrap gap-2">
                              <Badge>
                                {attempt.backendKind} #{attempt.attempt} ·{" "}
                                {attempt.state}
                              </Badge>
                              <code>{attempt.id}</code>
                              {attempt.hostId && (
                                <EntityIdentityText
                                  identities={identities}
                                  type="runtime_host"
                                  entityRef={attempt.hostId}
                                />
                              )}
                              {attempt.sessionRef && (
                                <Button asChild size="sm" variant="outline">
                                  <Link
                                    to={scope.scopedPath(
                                      `/work/sessions/${attempt.sessionRef}`,
                                    )}
                                  >
                                    Session
                                  </Link>
                                </Button>
                              )}
                            </div>
                            {attempt.sessionId && !attempt.sessionRef && (
                              <div className="mt-1 text-xs text-amber-700">
                                Runtime session {attempt.sessionId} has no
                                resolved diagnostics link.
                              </div>
                            )}
                            {attempt.providerSessionId && (
                              <div className="mt-1 text-xs text-muted-foreground">
                                Provider session: {attempt.providerSessionId}
                              </div>
                            )}
                            {attempt.failureMessage && (
                              <p className="mt-1 text-xs text-red-600">
                                {attempt.failureCode}: {attempt.failureMessage}
                              </p>
                            )}
                            {attempt.result != null && (
                              <pre className="mt-2 max-h-48 overflow-auto whitespace-pre-wrap rounded bg-muted p-2 text-xs">
                                {JSON.stringify(attempt.result, null, 2)}
                              </pre>
                            )}
                          </div>
                        ))}
                    </div>
                    {task.result != null && (
                      <pre className="mt-2 max-h-48 overflow-auto whitespace-pre-wrap rounded bg-background p-2 text-xs">
                        {JSON.stringify(task.result, null, 2)}
                      </pre>
                    )}
                  </div>
                ))}
              </div>
            </div>
          </section>
          <aside className="space-y-4">
            <div className="rounded-xl border bg-white p-4">
              <h2 className="font-semibold">Signal</h2>
              <div className="mt-3 flex gap-2">
                <Input
                  value={signal}
                  onChange={(event) => setSignal(event.target.value)}
                  placeholder="signal name"
                />
                <Button
                  disabled={
                    !canEdit ||
                    !signal ||
                    send.isPending ||
                    terminal.has(run.state)
                  }
                  onClick={() => send.mutate()}
                >
                  Send
                </Button>
              </div>
            </div>
            <div className="rounded-xl border bg-white p-4">
              <h2 className="font-semibold">Usage / policy</h2>
              <pre className="mt-3 overflow-auto rounded bg-muted p-3 text-xs">
                {JSON.stringify(
                  { usage: run.usage, policy: run.policySnapshot },
                  null,
                  2,
                )}
              </pre>
            </div>
            <div className="rounded-xl border bg-white p-4">
              <h2 className="font-semibold">Events</h2>
              <p className="mt-1 text-xs text-muted-foreground">
                Provider-native events, dispatch diagnostics, and structured
                tool failures are retained on the same Run timeline.
              </p>
              <ol className="mt-3 max-h-[40rem] space-y-2 overflow-auto">
                {events.data?.events.map((event) => {
                  const eventTask = tasks.find(
                    (task) => task.id === event.agentTaskId,
                  );
                  const failed = event.type === "agent_tool.failed";
                  const deferred =
                    event.type === "agent-task.dispatch_deferred";
                  const recovered =
                    event.type === "agent-task.dispatch_recovered";
                  return (
                    <li
                      key={event.id}
                      className={
                        failed
                          ? "border-l-2 border-red-500 bg-red-50 p-2 text-xs text-red-800"
                          : deferred
                            ? "border-l-2 border-amber-500 bg-amber-50 p-2 text-xs text-amber-900"
                            : recovered
                              ? "border-l-2 border-emerald-500 bg-emerald-50 p-2 text-xs text-emerald-900"
                              : "border-l-2 pl-3 text-xs"
                      }
                    >
                      <strong>
                        #{event.sequence} {event.type}
                      </strong>
                      <div className="mt-1 text-muted-foreground">
                        {new Date(event.occurredAt).toLocaleString()}
                        {eventTask && (
                          <>
                            {" "}
                            ·{" "}
                            <Link
                              className="text-primary"
                              to={scope.scopedPath(
                                `/work/issues/${String(eventTask.issueId)}`,
                              )}
                            >
                              <EntityIdentityText
                                identities={identities}
                                type="issue"
                                entityRef={String(eventTask.issueId)}
                              />
                            </Link>{" "}
                            ·{" "}
                            <Link
                              className="text-primary"
                              to={scope.scopedPath(
                                `/work/executions/tasks/${eventTask.id}`,
                              )}
                            >
                              Task {eventTask.id.slice(0, 8)}
                            </Link>
                          </>
                        )}
                        {event.attemptId && (
                          <> · Attempt {event.attemptId.slice(0, 8)}</>
                        )}
                      </div>
                      <div
                        className={
                          failed
                            ? "text-red-700"
                            : deferred
                              ? "text-amber-800"
                              : recovered
                                ? "text-emerald-800"
                                : "text-muted-foreground"
                        }
                      >
                        {entityDisplayName(
                          identities,
                          event.actor.type,
                          event.actor.ref,
                        )}
                      </div>
                      {event.payload != null && (
                        <details
                          className="mt-1"
                          open={failed || deferred || recovered}
                        >
                          <summary className="cursor-pointer text-muted-foreground">
                            Payload
                          </summary>
                          <pre className="mt-1 overflow-auto whitespace-pre-wrap rounded bg-muted p-2">
                            {JSON.stringify(event.payload, null, 2)}
                          </pre>
                        </details>
                      )}
                    </li>
                  );
                })}
              </ol>
            </div>
          </aside>
        </div>
      </details>
    </Page>
  );
}
