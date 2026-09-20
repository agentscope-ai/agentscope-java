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

import { useCallback, useEffect, useRef, useState } from "react";
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useSearchParams } from "react-router-dom";
import { Archive, ArrowLeft, CircleAlert, Filter, Inbox, ShieldCheck } from "lucide-react";
import ReactMarkdown from "react-markdown";
import { archiveInboxMessage, getInbox, getInboxSummary, listInbox, readInbox, type InboxItem } from "@/api/collaboration";
import { useControlPlaneScope } from "@/app/ScopeContext";
import { entityDisplayName, useEntityIdentities } from "@/components/EntityIdentity";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import ApprovalDetail from "@/features/approvals/ApprovalDetail";
import { IssueDetailContent } from "@/features/issues/IssueDetailPage";
import { WorkEmpty, WorkLoadingRows } from "@/features/work/WorkSurface";
import { formatRelative } from "@/lib/format";
import { cn } from "@/lib/utils";
import IssueReviewPanel from "./IssueReviewPanel";
import { INBOX_TYPES, inboxTypeLabel, retainInboxSelection } from "./inboxModel";

export default function InboxPage() {
  const scope = useControlPlaneScope();
  return <InboxWorkspace key={`${scope.tenant}/${scope.namespace}`} />;
}

function InboxWorkspace() {
  const scope = useControlPlaneScope();
  const qc = useQueryClient();
  const [params, setParams] = useSearchParams();
  const selectedId = params.get("item") || "";
  const view = ["attention", "unread", "all", "action"].includes(params.get("view") || "") ? params.get("view")! : "attention";
  const type = params.get("type") || "";
  const archived = params.get("archived") === "true";
  const [filtersOpen, setFiltersOpen] = useState(false);
  const [selectionIndex, setSelectionIndex] = useState(0);
  const [reviewNotes, setReviewNotes] = useState<Record<string, string>>({});
  const [approvalNotes, setApprovalNotes] = useState<Record<string, string>>({});
  const [preview, setPreview] = useState<{ itemId: string; issueId: string }>();
  const previewIssueId = preview?.itemId === selectedId ? preview.issueId : "";
  const setPreviewIssueId = (issueId: string) => setPreview({ itemId: selectedId, issueId });
  const attemptedRead = useRef(new Set<string>());
  const [readErrors, setReadErrors] = useState(new Set<string>());
  const list = useInfiniteQuery({
    queryKey: ["inbox", scope.tenant, scope.namespace, view, type, archived],
    queryFn: ({ pageParam }) => listInbox(scope.tenant, scope.namespace, { view, type, archived: String(archived), cursor: pageParam || undefined, limit: 50 }),
    initialPageParam: "", getNextPageParam: last => last.nextCursor || undefined, refetchInterval: 5000,
  });
  const summary = useQuery({ queryKey: ["inbox-summary", scope.tenant, scope.namespace], queryFn: () => getInboxSummary(scope.tenant, scope.namespace), refetchInterval: 5000 });
  const selected = useQuery({ queryKey: ["inbox-item", scope.tenant, scope.namespace, selectedId], queryFn: () => getInbox(selectedId, scope.tenant, scope.namespace), enabled: !!selectedId, refetchInterval: 5000 });
  const item = selected.data?.item;
  const items = retainInboxSelection(list.data?.pages.flatMap(page => page.items) || [], selected.isError ? undefined : item, selectionIndex);
  const identities = useEntityIdentities(items.map(entry => ({ type: entry.actor.type, ref: entry.actor.ref })));
  const counts = summary.data?.summary;
  const refreshInbox = useCallback(() => {
    for (const key of ["inbox", "inbox-summary", "inbox-item"]) void qc.invalidateQueries({ queryKey: [key] });
  }, [qc]);
  const mark = useMutation({
    mutationFn: (id: string) => readInbox(id, scope.tenant, scope.namespace),
    onSuccess: (data, id) => {
      qc.setQueryData(["inbox-item", scope.tenant, scope.namespace, id], data);
      setReadErrors(current => { const next = new Set(current); next.delete(id); return next; });
      refreshInbox();
    },
    onError: (_error, id) => setReadErrors(current => new Set(current).add(id)),
  });
  const markViewed = useCallback(() => {
    if (!item || item.read || attemptedRead.current.has(item.id)) return;
    attemptedRead.current.add(item.id); mark.mutate(item.id);
  }, [item, mark.mutate]);
  const archive = useMutation({
    mutationFn: (id: string) => archiveInboxMessage(id, scope.tenant, scope.namespace),
    onSuccess: (data, id) => { qc.setQueryData(["inbox-item", scope.tenant, scope.namespace, id], data); refreshInbox(); },
  });
  function changeParams(values: Record<string, string | undefined>) {
    setParams(current => {
      const next = new URLSearchParams(current);
      for (const [key, value] of Object.entries(values)) { if (value) next.set(key, value); else next.delete(key); }
      return next;
    });
  }
  const activeIssueId = previewIssueId || item?.issueId;
  return (
    <div className="flex h-full min-h-0 flex-col bg-white" data-testid="inbox-page">
      <header className="flex shrink-0 flex-wrap items-center justify-between gap-3 border-b border-slate-200 px-5 py-4">
        <div><h1 className="text-xl font-semibold text-slate-900">Inbox</h1><p className="mt-1 text-sm text-slate-500">Your messages, reviews, and decisions.</p></div>
        <div className="flex flex-wrap items-center gap-3 text-xs text-slate-500" aria-live="polite">
          {counts ? <><span><strong className="text-slate-900">{counts.unread}</strong> unread</span><span><strong className="text-amber-700">{counts.actionRequired}</strong> need action</span>{counts.pendingApprovals > 0 && <Badge tone="warning">{counts.pendingApprovals} pending approvals</Badge>}</> : <span>{summary.isError ? "Counts unavailable" : "Loading counts…"}</span>}
        </div>
      </header>
      <div className="grid min-h-0 flex-1 md:grid-cols-[360px_minmax(0,1fr)] xl:grid-cols-[390px_minmax(0,1fr)]">
        <section aria-label="Inbox messages" className={cn("flex min-h-0 flex-col border-r border-slate-200", selectedId && "hidden md:flex")}>
          <div className="shrink-0 space-y-3 border-b border-slate-200 p-3">
            <div className="flex items-center justify-between gap-2">
              <div className="flex rounded-lg bg-slate-100 p-0.5" aria-label="Inbox view">
                {(["attention", "unread", "all"] as const).map(value => <button key={value} type="button" aria-pressed={view === value && !archived} className={cn("rounded-md px-2.5 py-1.5 text-xs font-medium", view === value && !archived ? "bg-white text-slate-900 shadow-sm" : "text-slate-500")} onClick={() => changeParams({ view: value, archived: undefined, item: undefined })}>{value === "attention" ? "Attention" : value === "unread" ? "Unread" : "All"}</button>)}
              </div>
              <Button variant={filtersOpen || type || archived || view === "action" ? "secondary" : "ghost"} size="sm" aria-label="Filter inbox" aria-expanded={filtersOpen} onClick={() => setFiltersOpen(value => !value)}><Filter className="h-3.5 w-3.5" />Filter</Button>
            </div>
            {filtersOpen && <div className="grid gap-3 rounded-lg bg-slate-50 p-3">
              <label className="grid gap-1 text-xs font-medium text-slate-600">Message type<select aria-label="Message type" className="h-9 rounded-lg border border-slate-200 bg-white px-2 text-sm" value={type} onChange={event => changeParams({ type: event.target.value || undefined, item: undefined })}><option value="">All types</option>{Object.entries(INBOX_TYPES).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select></label>
              <label className="grid gap-1 text-xs font-medium text-slate-600">Status<select aria-label="Message status" className="h-9 rounded-lg border border-slate-200 bg-white px-2 text-sm" value={archived ? "archived" : view} onChange={event => changeParams({ view: event.target.value === "archived" ? "all" : event.target.value, archived: event.target.value === "archived" ? "true" : undefined, item: undefined })}><option value="attention">Needs attention</option><option value="action">Needs action</option><option value="unread">Unread</option><option value="all">All messages</option><option value="archived">Archived</option></select></label>
            </div>}
            {(type || archived) && <p className="text-xs text-slate-500">{type ? INBOX_TYPES[type] || type : "All types"}{archived && " · Archived"}</p>}
          </div>
          <div className="min-h-0 flex-1 overflow-y-auto" data-testid="inbox-list">
            {list.isPending ? <WorkLoadingRows /> : list.isError ? <WorkEmpty title="Messages unavailable" description="The inbox could not be loaded." action={<Button variant="outline" onClick={() => void list.refetch()}>Retry</Button>} /> : items.length ? items.map((entry, index) => <InboxRow key={entry.id} item={entry} selected={selectedId === entry.id} actor={entityDisplayName(identities, entry.actor.type, entry.actor.ref)} onSelect={() => { setSelectionIndex(index); changeParams({ item: entry.id }); }} />) : <WorkEmpty title="Inbox is clear" description="No messages match this view." />}
            {list.hasNextPage && <div className="p-4"><Button variant="outline" className="w-full" disabled={list.isFetchingNextPage} onClick={() => void list.fetchNextPage()}>{list.isFetchingNextPage ? "Loading…" : "Load more"}</Button></div>}
          </div>
        </section>
        <section aria-label="Message details" className={cn("flex min-h-0 min-w-0 flex-col", !selectedId && "hidden md:flex")}>
          {!selectedId ? <div className="flex flex-1 items-center justify-center"><WorkEmpty title="Select a message" description="Read an update, review an issue, or act on an approval." /></div> : <>
            <div className="flex shrink-0 items-center justify-between gap-2 border-b border-slate-200 px-4 py-2">
              <div className="flex min-w-0 items-center gap-2"><Button variant="ghost" size="sm" onClick={() => changeParams({ item: undefined })}><ArrowLeft className="h-4 w-4" /><span className="md:hidden">Inbox</span></Button><span className="truncate text-xs text-slate-500">{item ? inboxTypeLabel(item) : "Message"}{item?.archived && " · Archived"}</span></div>
              <div className="flex items-center gap-1">{item?.issueId && !item.approvalId && <Button asChild variant="ghost" size="sm"><Link to={scope.scopedPath(`/work/issues/${activeIssueId}`)}>Open issue ↗</Link></Button>}{item && !item.archived && !item.needsAction && <Button variant="ghost" size="sm" disabled={archive.isPending} onClick={() => archive.mutate(item.id)}><Archive className="h-3.5 w-3.5" />Archive</Button>}</div>
            </div>
            {item && readErrors.has(item.id) && <div role="alert" className="flex items-center justify-between bg-amber-50 px-4 py-2 text-xs text-amber-800">Could not mark this message as read.<Button size="sm" variant="ghost" disabled={mark.isPending} onClick={() => mark.mutate(item.id)}>Retry</Button></div>}
            {archive.isError && <p role="alert" className="bg-red-50 px-4 py-2 text-xs text-red-700">This message could not be archived. Pending actions must be completed first.</p>}
            {item?.type === "review_request" && item.issueId && !item.approvalId && activeIssueId === item.issueId && !selected.isError && <IssueReviewPanel key={item.id} item={item} note={reviewNotes[item.id] || ""} onNoteChange={note => setReviewNotes(current => ({ ...current, [item.id]: note }))} />}
            <div className="min-h-0 flex-1 overflow-y-auto" data-testid="inbox-detail">
              {selected.isPending ? <WorkLoadingRows /> : selected.isError || !item ? <WorkEmpty title="Message unavailable" description="The message could not be loaded, or is not visible in this workspace." action={<Button variant="outline" onClick={() => void selected.refetch()}>Retry</Button>} /> : item.approvalId ? <ApprovalDetail key={item.id} approvalId={item.approvalId} note={approvalNotes[item.approvalId] || ""} onNoteChange={note => setApprovalNotes(current => ({ ...current, [item.approvalId!]: note }))} onReady={markViewed} /> : activeIssueId ? <>
                <div className="border-b border-slate-100 bg-slate-50/70 px-5 py-3"><p className="text-xs font-medium text-slate-600">{item.title} · {formatRelative(item.createdAt)}</p>{item.body && <p className="mt-1 line-clamp-3 whitespace-pre-wrap text-xs leading-5 text-slate-500">{item.body}</p>}{previewIssueId && previewIssueId !== item.issueId && <Button variant="link" size="sm" className="mt-1 h-auto p-0" onClick={() => setPreviewIssueId("")}>Back to notified issue</Button>}</div>
                <IssueDetailContent key={`${item.id}/${activeIssueId}`} issueId={activeIssueId} embedded focusCommentId={activeIssueId === item.issueId ? item.commentId : undefined} onReady={markViewed} onBack={() => previewIssueId ? setPreviewIssueId("") : changeParams({ item: undefined })} onNavigateIssue={setPreviewIssueId} />
              </> : <MessageDetail key={item.id} item={item} onReady={markViewed} />}
            </div>
          </>}
        </section>
      </div>
    </div>
  );
}

function InboxRow({ item, selected, actor, onSelect }: { item: InboxItem; selected: boolean; actor: string; onSelect: () => void }) {
  const approval = !!item.approvalId;
  const alert = item.severity === "error" || item.type === "issue_blocked";
  return <button type="button" data-inbox-id={item.id} aria-current={selected ? "true" : undefined} className={cn("flex w-full gap-3 border-b border-slate-100 px-4 py-4 text-left transition hover:bg-slate-50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-indigo-400", selected ? "bg-indigo-50/70 ring-1 ring-inset ring-indigo-100" : !item.read && "bg-indigo-50/20")} onClick={onSelect}>
    <span className={cn("mt-0.5 shrink-0 rounded-lg p-2", approval ? "bg-amber-50 text-amber-700" : alert ? "bg-red-50 text-red-600" : "bg-slate-100 text-slate-500")}>{approval ? <ShieldCheck className="h-4 w-4" /> : alert ? <CircleAlert className="h-4 w-4" /> : <Inbox className="h-4 w-4" />}</span>
    <span className="min-w-0 flex-1">
      <span className="flex items-center gap-2"><span className={cn("truncate text-sm text-slate-900", !item.read ? "font-semibold" : "font-medium")}>{item.title}</span>{!item.read && <span aria-label="Unread" className="h-1.5 w-1.5 shrink-0 rounded-full bg-indigo-500" />}</span>
      <span className="mt-1.5 flex"><Badge tone={approval ? "warning" : alert ? "danger" : "info"}>{inboxTypeLabel(item)}</Badge></span>
      <span className="mt-2 line-clamp-2 break-words text-xs leading-5 text-slate-500">{item.body || "Open to view details"}</span>
      {item.type === "review_request" && item.needsAction && <span className="mt-2 block text-xs font-medium text-indigo-700">Review result →</span>}
      <span className="mt-2 block truncate text-[11px] text-slate-400">{actor} · {formatRelative(item.createdAt)}</span>
    </span>
  </button>;
}

function MessageDetail({ item, onReady }: { item: InboxItem; onReady: () => void }) {
  useEffect(() => { onReady(); }, [onReady]);
  return <article className="mx-auto max-w-3xl p-6 sm:p-8"><h2 className="text-xl font-semibold">{item.title}</h2><p className="mt-2 text-xs text-slate-500">{new Date(item.createdAt).toLocaleString()}</p><div className="md-text mt-6 break-words text-sm leading-7"><ReactMarkdown>{item.body || "No additional details."}</ReactMarkdown></div></article>;
}
