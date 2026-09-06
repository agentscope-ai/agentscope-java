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
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, ChevronRight, CircleAlert, Inbox, ShieldCheck, X } from "lucide-react";
import { Link } from "react-router-dom";

import {
  decideApproval,
  listApprovals,
  listInbox,
  readInbox,
  type Approval,
} from "@/api/collaboration";
import { useControlPlaneScope } from "@/app/ScopeContext";
import { EntityIdentityText, entityDisplayName, useEntityIdentities } from "@/components/EntityIdentity";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/input";
import {
  WorkEmpty,
  WorkLoadingRows,
  WorkPage,
  WorkPageHeader,
  WorkPanel,
  WorkPanelHeader,
  WorkStatusBadge,
} from "@/features/work/WorkSurface";
import { formatRelative } from "@/lib/format";
import { cn } from "@/lib/utils";

import {
  managedToolApprovalExpiry,
  managedToolApprovalRequest,
} from "./managedToolApproval";

export default function ApprovalsPage() {
  const scope = useControlPlaneScope();
  const qc = useQueryClient();
  const [inboxView, setInboxView] = useState<"unread" | "all">("unread");
  const [notes, setNotes] = useState<Record<string, string>>({});
  const [expanded, setExpanded] = useState<string>();
  const approvals = useQuery({
    queryKey: ["approvals", scope.tenant, scope.namespace],
    queryFn: () => listApprovals(scope.tenant, scope.namespace),
    refetchInterval: 5000,
  });
  const inbox = useQuery({
    queryKey: ["inbox", scope.tenant, scope.namespace],
    queryFn: () => listInbox(scope.tenant, scope.namespace),
    refetchInterval: 5000,
  });
  const decide = useMutation({
    mutationFn: ({ item, status }: { item: Approval; status: string }) =>
      decideApproval(item.id, status, item.version, { note: notes[item.id] || "" }),
    onSuccess: (_data, variables) => {
      setNotes((current) => {
        const next = { ...current };
        delete next[variables.item.id];
        return next;
      });
      setExpanded(undefined);
      void qc.invalidateQueries({ queryKey: ["approvals"] });
      void qc.invalidateQueries({ queryKey: ["inbox"] });
    },
  });
  const mark = useMutation({
    mutationFn: readInbox,
    onSuccess: () => void qc.invalidateQueries({ queryKey: ["inbox"] }),
  });
  const approvalItems = approvals.data?.items || [];
  const allInboxItems = inbox.data?.items || [];
  const inboxItems = inboxView === "unread" ? allInboxItems.filter((item) => !item.read) : allInboxItems;
  const unreadCount = allInboxItems.filter((item) => !item.read).length;
  const identities = useEntityIdentities([
    ...approvalItems.flatMap((item) => [
      { type: item.targetType, ref: item.targetRef },
      { type: item.requestedBy.type, ref: item.requestedBy.ref },
      { type: "human", ref: item.approverRef },
    ]),
    ...allInboxItems.map((item) => ({ type: item.actor.type, ref: item.actor.ref })),
  ]);

  return (
    <WorkPage>
      <WorkPageHeader
        title="Inbox & approvals"
        description="Review agent requests, blocked work, mentions, and everything waiting on a human decision."
        actions={
          <div className="flex items-center gap-2 rounded-full border border-slate-200 bg-white px-3 py-1.5 text-sm text-slate-600">
            <span className={cn("h-2 w-2 rounded-full", unreadCount ? "bg-indigo-500" : "bg-emerald-500")} />
            {unreadCount ? `${unreadCount} unread` : "All caught up"}
          </div>
        }
      />

      <div className="grid gap-5 xl:grid-cols-[minmax(0,0.9fr)_minmax(0,1.1fr)]">
        <WorkPanel>
          <WorkPanelHeader
            title="Inbox"
            description="Notifications and follow-ups"
            action={
              <div className="flex rounded-lg bg-slate-100 p-0.5">
                {(["unread", "all"] as const).map((item) => (
                  <button
                    key={item}
                    type="button"
                    onClick={() => setInboxView(item)}
                    className={cn(
                      "rounded-md px-2.5 py-1.5 text-xs font-medium capitalize",
                      inboxView === item ? "bg-white text-slate-900 shadow-sm" : "text-slate-500",
                    )}
                  >
                    {item}
                  </button>
                ))}
              </div>
            }
          />
          {inbox.isLoading ? (
            <WorkLoadingRows />
          ) : inboxItems.length ? (
            <div className="divide-y divide-slate-100">
              {inboxItems.map((item) => (
                <article key={item.id} className={cn("group flex gap-3 px-5 py-4", !item.read && "bg-indigo-50/25")}>
                  <span className={cn(
                    "mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-lg",
                    item.severity === "error" ? "bg-red-50 text-red-600" : "bg-slate-100 text-slate-500",
                  )}>
                    {item.severity === "error" ? <CircleAlert className="h-4 w-4" /> : <Inbox className="h-4 w-4" />}
                  </span>
                  <div className="min-w-0 flex-1">
                    <div className="flex items-start justify-between gap-3">
                      <div className="min-w-0">
                        <div className="flex items-center gap-2">
                          <h3 className="truncate text-sm font-medium text-slate-900">{item.title}</h3>
                          {!item.read && <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-indigo-500" />}
                        </div>
                        <p className="mt-1 text-sm leading-5 text-slate-500">{item.body || item.type}</p>
                      </div>
                      <Badge tone={item.severity === "error" ? "danger" : "info"}>{item.type.replace(/_/g, " ")}</Badge>
                    </div>
                    <div className="mt-3 flex items-center justify-between gap-3">
                      <span className="text-xs text-slate-400">{entityDisplayName(identities, item.actor.type, item.actor.ref)} · {formatRelative(item.createdAt)}</span>
                      <div className="flex items-center gap-1">
                        {item.issueId && (
                          <Button size="sm" variant="ghost" asChild>
                            <Link to={scope.scopedPath(`/work/issues/${item.issueId}`)}>Open issue <ChevronRight className="h-3.5 w-3.5" /></Link>
                          </Button>
                        )}
                        {!item.read && (
                          <Button size="sm" variant="ghost" disabled={mark.isPending} onClick={() => mark.mutate(item.id)}>
                            <Check className="h-3.5 w-3.5" /> Mark read
                          </Button>
                        )}
                      </div>
                    </div>
                  </div>
                </article>
              ))}
            </div>
          ) : (
            <WorkEmpty
              title={inboxView === "unread" ? "Inbox is clear" : "No notifications yet"}
              description="Mentions, failures, blocked routes, and follow-up requests will appear here."
            />
          )}
        </WorkPanel>

        <WorkPanel>
          <WorkPanelHeader
            title="Pending approvals"
            description={`${approvalItems.length} request${approvalItems.length === 1 ? "" : "s"} waiting for a decision`}
          />
          {approvals.isLoading ? (
            <WorkLoadingRows />
          ) : approvalItems.length ? (
            <div className="divide-y divide-slate-100">
              {approvalItems.map((item) => {
                const isOpen = expanded === item.id;
                const managedRequest = managedToolApprovalRequest(item.request);
                const expiresAt = managedToolApprovalExpiry(managedRequest);
                const expired = expiresAt != null && expiresAt <= Date.now();
                return (
                  <article key={item.id} className="px-5 py-5">
                    <div className="flex gap-3">
                      <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-amber-50 text-amber-700">
                        <ShieldCheck className="h-4 w-4" />
                      </span>
                      <div className="min-w-0 flex-1">
                        <div className="flex flex-wrap items-center justify-between gap-2">
                          <div>
                            <h3 className="text-sm font-semibold text-slate-900">
                              {managedRequest ? (
                                <>Confirm {managedRequest.backendKind ?? "managed"} tool: <span className="font-mono">{managedRequest.toolName}</span></>
                              ) : (
                                <EntityIdentityText identities={identities} type={item.targetType} entityRef={item.targetRef} />
                              )}
                            </h3>
                            <p className="mt-0.5 text-[11px] capitalize text-slate-400">{item.targetType.replace(/_/g, " ")}</p>
                          </div>
                          <WorkStatusBadge status={item.status} />
                        </div>
                        <p className="mt-3 text-sm leading-6 text-slate-600">{item.reason || "Approval is required before this action can continue."}</p>
                        <div className="mt-3 flex flex-wrap items-center gap-x-4 gap-y-2 text-xs text-slate-400">
                          <span>Requested by {entityDisplayName(identities, item.requestedBy.type, item.requestedBy.ref)} · {formatRelative(item.createdAt)}</span>
                          {expiresAt != null && (
                            <span className={expired ? "font-medium text-red-600" : "text-amber-700"}>
                              {expired ? "Expired" : `Expires ${new Date(expiresAt).toLocaleString()}`}
                            </span>
                          )}
                          {item.issueId && <Link className="font-medium text-indigo-600 hover:text-indigo-700" to={scope.scopedPath(`/work/issues/${item.issueId}`)}>View related issue</Link>}
                          {item.runId && <Link className="font-medium text-indigo-600 hover:text-indigo-700" to={scope.scopedPath(`/work/executions/${item.runId}`)}>View execution</Link>}
                          {managedRequest && <Link className="font-medium text-indigo-600 hover:text-indigo-700" to={scope.scopedPath(`/work/executions/tasks/${managedRequest.agentTaskId}`)}>View task</Link>}
                          {managedRequest?.sessionRef && <Link className="font-medium text-indigo-600 hover:text-indigo-700" to={scope.scopedPath(`/work/sessions/${managedRequest.sessionRef}`)}>View session</Link>}
                          {item.request != null && (
                            <button type="button" className="font-medium text-slate-600 hover:text-slate-900" onClick={() => setExpanded(isOpen ? undefined : item.id)}>
                              {isOpen ? "Hide request" : "Review request"}
                            </button>
                          )}
                        </div>
                        {isOpen && (
                          <div className="mt-4 space-y-3 rounded-xl border border-slate-200 bg-slate-50/60 p-4">
                            {managedRequest?.inputPreview != null && (
                              <div>
                                <p className="mb-1 text-xs font-medium text-slate-500">Redacted tool input</p>
                                <pre className="max-h-48 overflow-auto whitespace-pre-wrap break-all rounded-lg bg-white p-3 text-xs leading-5 text-slate-700">
                                  {JSON.stringify(managedRequest.inputPreview, null, 2)}
                                </pre>
                              </div>
                            )}
                            <details>
                              <summary className="cursor-pointer text-xs font-medium text-slate-500">Diagnostic request</summary>
                              <pre className="mt-2 max-h-48 overflow-auto whitespace-pre-wrap break-all text-xs leading-5 text-slate-600">{JSON.stringify(item.request || {}, null, 2)}</pre>
                            </details>
                            <Textarea
                              className="min-h-20 bg-white shadow-none"
                              value={notes[item.id] || ""}
                              onChange={(event) => setNotes((current) => ({ ...current, [item.id]: event.target.value }))}
                              placeholder="Add a decision note (optional)"
                            />
                          </div>
                        )}
                        <div className="mt-4 flex flex-wrap gap-2">
                          <Button size="sm" disabled={decide.isPending || expired} onClick={() => decide.mutate({ item, status: "approved" })}>
                            <Check className="h-4 w-4" /> Approve
                          </Button>
                          <Button size="sm" variant="outline" disabled={decide.isPending || expired} onClick={() => decide.mutate({ item, status: "rejected" })}>
                            <X className="h-4 w-4" /> Reject
                          </Button>
                          {!isOpen && <Button size="sm" variant="ghost" onClick={() => setExpanded(item.id)}>Add note</Button>}
                        </div>
                      </div>
                    </div>
                  </article>
                );
              })}
            </div>
          ) : (
            <WorkEmpty title="No pending approvals" description="Agent requests that need an explicit human decision will appear here." />
          )}
          {decide.isError && <p className="border-t border-red-100 bg-red-50 px-5 py-3 text-sm text-red-700">The decision could not be saved. Refresh the request and try again.</p>}
        </WorkPanel>
      </div>
    </WorkPage>
  );
}
