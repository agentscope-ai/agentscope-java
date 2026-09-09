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

import { IssueSharing } from "./IssueSharing";
import { useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { useMutation, useQueries, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate, useParams } from "react-router-dom";
import ReactMarkdown from "react-markdown";
import {
  Archive,
  ArrowLeft,
  ArrowUp,
  Bot,
  CalendarDays,
  Check,
  CheckCircle2,
  ChevronDown,
  ChevronRight,
  Circle,
  CircleDot,
  ClipboardList,
  Clock3,
  Download,
  ExternalLink,
  File,
  GitPullRequest,
  ListChecks,
  LoaderCircle,
  MessageSquare,
  PanelRight,
  Paperclip,
  Pencil,
  Plus,
  Reply,
  Sparkles,
  UserRound,
  UsersRound,
  X,
} from "lucide-react";
import {
  acceptIssue,
  addComment,
  archiveIssue,
  assignIssue,
  createChildIssue,
  exportIssue,
  downloadIssueArtifact,
  listTeams,
  getIssue,
  getIssueSummary,
  listChildIssues,
  listComments,
  listIssueActivity,
  listIssueArtifacts,
  listIssueSubscribers,
  listIssueTasks,
  rejectIssue,
  reopenIssue,
  resolveComment,
  subscribeIssue,
  transitionIssue,
  unsubscribeIssue,
  updateIssue,
  uploadIssueArtifact,
  type Actor,
  type Comment,
  type IssueActivity,
} from "@/api/collaboration";
import { listRunEvents, listRuns, type RunEvent } from "@/api/orchestration";
import { useControlPlaneScope } from "@/app/ScopeContext";
import { AgentPicker } from "@/components/AgentPicker";
import {
  EntityIdentityText,
  entityDisplayName,
  type EntityIdentityMap,
  useEntityIdentities,
} from "@/components/EntityIdentity";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input, Textarea } from "@/components/ui/input";
import { getUsername } from "@/lib/auth";
import { NamedResourcePicker } from "@/components/NamedResourcePicker";
import { IssueAcceptanceEditor } from "./IssueAcceptanceEditor";
import { dateTimeLocal, dueDatePatch, saveIssueDownload, sourceWebUrl } from "./issueProperties";
import { formatRelative } from "@/lib/format";
import { cn } from "@/lib/utils";

const STATUS_LABELS: Record<string, string> = {
  backlog: "Backlog",
  todo: "Todo",
  in_progress: "In progress",
  in_review: "In review",
  blocked: "Blocked",
  done: "Done",
  cancelled: "Cancelled",
};

const STATUS_OPTIONS: Record<string, string[]> = {
  backlog: ["backlog", "todo", "in_progress", "cancelled"],
  todo: ["todo", "backlog", "in_progress", "blocked", "cancelled"],
  in_progress: ["in_progress", "in_review", "blocked", "done", "cancelled"],
  in_review: ["in_review", "in_progress", "blocked", "done", "cancelled"],
  blocked: ["blocked", "todo", "in_progress", "cancelled"],
  done: ["done", "in_progress"],
  cancelled: ["cancelled"],
};

function initials(actor: Actor, identities: EntityIdentityMap) {
  if (actor.type === "system" || actor.type === "automation") return actor.type === "system" ? "S" : "A";
  const value = entityDisplayName(identities, actor.type, actor.ref);
  return value.slice(0, 1).toUpperCase();
}

function ActorAvatar({ actor, identities, size = "md" }: { actor: Actor; identities: EntityIdentityMap; size?: "sm" | "md" }) {
  const Icon = actor.type === "agent" ? Bot : actor.type === "human" ? UserRound : Sparkles;
  return (
    <span
      className={cn(
        "inline-flex shrink-0 items-center justify-center rounded-full border border-slate-200 bg-slate-50 font-semibold text-slate-600",
        size === "sm" ? "h-6 w-6 text-[10px]" : "h-8 w-8 text-xs",
      )}
      title={`${entityDisplayName(identities, actor.type, actor.ref)} · ${actor.type}: ${actor.ref || "system"}`}
    >
      {actor.ref ? initials(actor, identities) : <Icon className={size === "sm" ? "h-3 w-3" : "h-4 w-4"} />}
    </span>
  );
}

function actorName(actor: Actor, identities: EntityIdentityMap) {
  return entityDisplayName(identities, actor.type, actor.ref);
}

function formatBytes(value: number) {
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / 1024 / 1024).toFixed(1)} MB`;
}

function mutationMessage(error: unknown) {
  if (!(error instanceof Error)) return "Something went wrong. Please try again.";
  if (error.message.includes("store: conflict")) return "This issue changed or this action is no longer available. Refresh the issue and try again.";
  try {
    const parsed = JSON.parse(error.message) as { error?: string };
    return parsed.error || error.message;
  } catch {
    return error.message;
  }
}

function activityDescription(activity: IssueActivity, identities: EntityIdentityMap) {
  const name = actorName(activity.actor, identities);
  const details = activity.details || {};
  switch (activity.action) {
    case "issue.created":
      return <><strong>{name}</strong> created the issue</>;
    case "issue.updated":
      return <><strong>{name}</strong> updated the issue details</>;
    case "issue.assigned":
      if (!activity.objectRef) return <><strong>{name}</strong> removed the assignee</>;
      return <><strong>{name}</strong> assigned this issue to <strong>{entityDisplayName(identities, activity.objectType, activity.objectRef)}</strong></>;
    case "issue.status_changed":
      if (details.from === "in_review" && details.to === "in_progress") return <><strong>{name}</strong> requested changes{typeof details.reason === "string" && details.reason && <span className="mt-1 block whitespace-pre-wrap text-slate-700">{details.reason}</span>}</>;
      if (details.from === "in_review" && details.to === "done") return <><strong>{name}</strong> accepted the result</>;
      return <><strong>{name}</strong> changed status from <strong>{STATUS_LABELS[String(details.from)] || String(details.from || "—")}</strong> to <strong>{STATUS_LABELS[String(details.to)] || String(details.to || "—")}</strong></>;
    case "agent_task.completed":
      return <><strong>{name}</strong> completed an Agent task</>;
    case "issue.archived":
      return <><strong>{name}</strong> archived the issue</>;
    default:
      return <><strong>{name}</strong> {activity.action.replace(/[._]/g, " ")}</>;
  }
}

function PropertyRow({
  icon: Icon,
  label,
  children,
}: {
  icon: typeof Circle;
  label: string;
  children: React.ReactNode;
}) {
  return (
    <div className="grid grid-cols-[7rem_minmax(0,1fr)] items-start gap-3 py-2.5 text-sm">
      <div className="flex items-center gap-2 text-muted-foreground">
        <Icon className="h-4 w-4" />
        <span>{label}</span>
      </div>
      <div className="min-w-0">{children}</div>
    </div>
  );
}

function CommentBody({ entry, identities }: { entry: Comment; identities: EntityIdentityMap }) {
  return (
    <div className="min-w-0 flex-1">
      <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
        <span className="text-sm font-semibold text-slate-800">{actorName(entry.author, identities)}</span>
        {entry.type !== "comment" && <Badge tone={entry.type === "result" ? "success" : "info"}>{entry.type}</Badge>}
        <span className="text-xs text-muted-foreground">{formatRelative(entry.createdAt)}</span>
        {entry.resolvedAt && <span className="inline-flex items-center gap-1 text-xs font-medium text-emerald-700"><CheckCircle2 className="h-3.5 w-3.5" /> Resolved</span>}
      </div>
      {entry.externalSync?.lastError && <p className="mt-2 text-xs text-red-600">External sync: {entry.externalSync.lastError}</p>}
      <div className={cn("md-text mt-2 text-[15px] text-slate-700", entry.deletedAt && "italic text-muted-foreground")}>
        {entry.deletedAt ? "This comment was deleted." : <ReactMarkdown>{entry.content}</ReactMarkdown>}
      </div>
      {!!entry.routes?.length && (
        <div className="mt-3 flex flex-wrap gap-1.5">
          {entry.routes.map((route) => (
            <Badge key={`${route.targetType}-${route.targetRef}-${route.outcome}`} tone={route.outcome === "blocked" ? "danger" : "info"}>
              @<EntityIdentityText identities={identities} type={route.targetType} entityRef={route.targetRef} /> · {route.outcome}
            </Badge>
          ))}
        </div>
      )}
    </div>
  );
}

function toolFailures(events: RunEvent[] | undefined) {
  return (events || []).filter((event) => event.type === "agent_tool.failed");
}

function dispatchDeferrals(events: RunEvent[] | undefined) {
  return (events || []).filter((event) => event.type === "agent-task.dispatch_deferred");
}

function toolFailureMessage(event: RunEvent | undefined): string {
  if (!event?.payload || typeof event.payload !== "object") return "Tool call failed";
  const payload = event.payload as Record<string, unknown>;
  const tool = String(payload.toolName || "tool");
  const message = String(payload.message || "Tool call failed");
  return `${tool}: ${message}`;
}

function dispatchDeferredMessage(event: RunEvent | undefined): string {
  if (!event?.payload || typeof event.payload !== "object") return "Agent task dispatch was deferred";
  const payload = event.payload as Record<string, unknown>;
  return String(payload.message || "Agent task dispatch was deferred");
}

export default function IssueDetailPage() {
  const { issueId = "" } = useParams();
  return <IssueDetailContent key={issueId} issueId={issueId} />;
}

export function IssueDetailContent({ issueId, embedded = false, focusCommentId, onReady, onBack, onNavigateIssue }: {
  issueId: string;
  embedded?: boolean;
  focusCommentId?: string;
  onReady?: () => void;
  onBack?: () => void;
  onNavigateIssue?: (id: string) => void;
}) {
  const container = useRef<HTMLDivElement>(null);
  const focused = useRef<string>();
  const scope = useControlPlaneScope();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const username = getUsername();
  const [content, setContent] = useState("");
  const [commentType, setCommentType] = useState("comment");
  const [mentionOpen, setMentionOpen] = useState(false);
  const [mentionType, setMentionType] = useState("agent");
  const [mentionRef, setMentionRef] = useState("");
  const [replyingTo, setReplyingTo] = useState<string>();
  const [replyContent, setReplyContent] = useState("");
  const [assigneeOpen, setAssigneeOpen] = useState(false);
  const [assigneeType, setAssigneeType] = useState("agent");
  const [assigneeRef, setAssigneeRef] = useState("");
  const [childOpen, setChildOpen] = useState(false);
  const [childTitle, setChildTitle] = useState("");
  const [artifactFile, setArtifactFile] = useState<File | null>(null);
  const [detailsOpen, setDetailsOpen] = useState(() => !embedded && typeof window !== "undefined" && window.innerWidth >= 1280);
  const [editingTitle, setEditingTitle] = useState(false);
  const [editingDescription, setEditingDescription] = useState(false);
  const [titleDraft, setTitleDraft] = useState("");
  const [descriptionDraft, setDescriptionDraft] = useState("");
  const [dueDraft, setDueDraft] = useState<string | null>(null);
  const [propertiesOpen, setPropertiesOpen] = useState(true);

  const issue = useQuery({ queryKey: ["issue", issueId], queryFn: () => getIssue(issueId), enabled: !!issueId, refetchInterval: 5000 });
  const comments = useQuery({ queryKey: ["comments", issueId, focusCommentId || ""], queryFn: () => listComments(issueId, focusCommentId), enabled: !!issueId, refetchInterval: 5000 });
  const activities = useQuery({ queryKey: ["issue-activity", issueId], queryFn: () => listIssueActivity(issueId), enabled: !!issueId, refetchInterval: 5000 });
  const subscribers = useQuery({ queryKey: ["issue-subscribers", issueId], queryFn: () => listIssueSubscribers(issueId), enabled: !!issueId, refetchInterval: 5000 });
  const artifacts = useQuery({ queryKey: ["issue-artifacts", issueId], queryFn: () => listIssueArtifacts(issueId), enabled: !!issueId, refetchInterval: 5000 });
  const tasks = useQuery({ queryKey: ["issue-tasks", scope.tenant, scope.namespace, issueId], queryFn: () => listIssueTasks(scope.tenant, scope.namespace, issueId), enabled: !!issueId, refetchInterval: 3000 });
  const summary = useQuery({ queryKey: ["issue-summary", issueId], queryFn: () => getIssueSummary(issueId), enabled: !!issueId });
  const children = useQuery({ queryKey: ["issue-children", issueId], queryFn: () => listChildIssues(scope.tenant, scope.namespace, issueId), enabled: !!issueId });
  const runs = useQuery({ queryKey: ["issue-runs", issueId], queryFn: () => listRuns(scope.tenant, scope.namespace, issueId), enabled: !!issueId, refetchInterval: 3000 });
  const runEvents = useQueries({ queries: (runs.data?.runs || []).map((run) => ({
    queryKey: ["run-events", run.id],
    queryFn: () => listRunEvents(run.id),
    refetchInterval: ["succeeded", "partial_succeeded", "failed", "cancelled"].includes(run.state) ? false : 3000,
  })) });

  const teams = useQuery({
    queryKey: ["issue-owner-teams", scope.tenant, scope.namespace],
    queryFn: () => listTeams(scope.tenant, scope.namespace),
    enabled: assigneeOpen && assigneeType === "team",
    staleTime: 10_000,
  });
  const teamOptions = (teams.data?.items || []).filter(team => team.status === "active")
    .map(team => ({ id: team.id, label: team.name, secondary: "Team" }));

  const item = issue.data?.issue;
  useEffect(() => {
    if (issue.isSuccess && (!focusCommentId || comments.isSuccess)) onReady?.();
  }, [issue.isSuccess, comments.isSuccess, focusCommentId, onReady]);
  useEffect(() => {
    if (!focusCommentId || !comments.isSuccess || focused.current === focusCommentId) return;
    const element = container.current?.querySelector(`[id="comment-${focusCommentId}"]`);
    if (element) {
      element.scrollIntoView({ block: "center" });
      focused.current = focusCommentId;
    }
  }, [focusCommentId, comments.data, comments.isSuccess]);
  useEffect(() => {
    if (!editingTitle) setTitleDraft(item?.title || "");
  }, [item?.title, editingTitle]);
  useEffect(() => {
    if (!editingDescription) setDescriptionDraft(item?.description || "");
  }, [item?.description, editingDescription]);

  const refresh = () => Promise.all([
    ...["issue", "issue-summary", "issue-activity"].map(key => qc.invalidateQueries({ queryKey: [key, issueId] })),
    ...["issues", "inbox", "inbox-summary", "inbox-item"].map(key => qc.invalidateQueries({ queryKey: [key] })),
  ]);
  const refreshDiscussion = () => Promise.all(
    ["issue", "comments", "issue-activity", "issue-summary"].map(key => qc.invalidateQueries({ queryKey: [key, issueId] })),
  );

  const update = useMutation({
    mutationFn: (body: Record<string, unknown>) => updateIssue(issueId, { ...body, expectedVersion: issue.data?.issue.version }),
    onError: () => { void qc.invalidateQueries({ queryKey: ["issue", issueId] }); },
    onSuccess: async (_data, body) => {
      await refresh();
      if ("title" in body) setEditingTitle(false);
      if ("description" in body) setEditingDescription(false);
      if ("dueAt" in body) setDueDraft(null);
    },
  });
  const comment = useMutation({
    mutationFn: ({ text, parentId }: { text: string; parentId?: string }) =>
      addComment(issueId, text, parentId, !parentId && mentionRef ? [{ type: mentionType, ref: mentionRef }] : [], parentId ? "comment" : commentType),
    onSuccess: async (_data, variables) => {
      if (variables.parentId) {
        setReplyContent("");
        setReplyingTo(undefined);
      } else {
        setContent("");
        setCommentType("comment");
        setMentionRef("");
        setMentionOpen(false);
      }
      await refreshDiscussion();
    },
  });
  const resolve = useMutation({ mutationFn: ({ id, version, resolved }: { id: string; version: number; resolved: boolean }) => resolveComment(issueId, id, version, resolved), onSuccess: refreshDiscussion });
  const assign = useMutation({
    mutationFn: () => assignIssue(issueId, assigneeType, assigneeType ? assigneeRef.trim() : "", issue.data?.issue.version || 0),
    onSuccess: async () => {
      setAssigneeOpen(false);
      setAssigneeRef("");
      await refresh();
      await qc.invalidateQueries({ queryKey: ["issue-tasks"] });
      await qc.invalidateQueries({ queryKey: ["issue-runs", issueId] });
      void qc.invalidateQueries({ queryKey: ["tasks"] });
    },
  });
  const child = useMutation({
    mutationFn: () => createChildIssue(issueId, { title: childTitle, priority: "normal" }),
    onSuccess: () => {
      setChildTitle("");
      setChildOpen(false);
      refresh();
      void qc.invalidateQueries({ queryKey: ["issue-children", issueId] });
    },
  });
  const artifact = useMutation({
    mutationFn: () => uploadIssueArtifact(scope.tenant, scope.namespace, issueId, artifactFile!),
    onSuccess: async () => {
      setArtifactFile(null);
      await qc.invalidateQueries({ queryKey: ["issue-artifacts", issueId] });
      await refresh();
    },
  });
  const action = useMutation({
    onError: () => { void qc.invalidateQueries({ queryKey: ["issue", issueId] }); },
    mutationFn: async (next: string) => {
      const current = issue.data?.issue;
      if (!current || next === current.status) return;
      if (next === "done" && current.status === "in_review") await acceptIssue(issueId, current.version);
      else if (next === "in_progress" && current.status === "in_review") await rejectIssue(issueId, current.version, "Changes requested");
      else if (next === "in_progress" && current.status === "done") await reopenIssue(issueId, current.version);
      else if (next === "archive") await archiveIssue(issueId, current.version);
      else await transitionIssue(issueId, next, current.version);
    },
    onSuccess: async (_data, next) => {
      await refresh();
      if (next === "archive") { if (embedded) onBack?.(); else navigate(scope.scopedPath("/work/issues")); }
    },
  });
  const subscription = useMutation({
    mutationFn: async (subscribed: boolean) => {
      if (subscribed) await unsubscribeIssue(issueId, "human", username);
      else await subscribeIssue(issueId);
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: ["issue-subscribers", issueId] }),
  });
  const download = useMutation({
    mutationFn: () => exportIssue(issueId),
    onSuccess: (data) => saveIssueDownload(new Blob([JSON.stringify(data, null, 2)], { type: "application/json" }), `issue-${issueId}.json`),
  });
  const artifactDownload = useMutation({
    mutationFn: downloadIssueArtifact,
    onSuccess: (blob, entry) => saveIssueDownload(blob, entry.filename),
  });

  const allTasks = tasks.data?.items || [];
  const issueTasks = allTasks.filter((task) => task.issueId === issueId);
  const issueExecutions = (runs.data?.runs || []).map((run, index) => ({
    run,
    tasks: issueTasks.filter((task) => task.orchestrationRunId === run.id),
    toolFailures: toolFailures(runEvents[index]?.data?.events).filter(event => issueTasks.some(task => task.id === event.agentTaskId)),
    dispatchDeferrals: dispatchDeferrals(runEvents[index]?.data?.events).filter((event) =>
      issueTasks.some((task) => task.id === event.agentTaskId && task.status === "queued"),
    ),
  }));
  const identities = useEntityIdentities([
    { type: item?.assigneeType, ref: item?.assigneeRef },
    { type: item?.executionTargetType, ref: item?.executionTargetRef },
    { type: item?.creator.type, ref: item?.creator.ref },
    { type: item?.sourceType, ref: item?.sourceRef },
    ...(comments.data?.items || []).flatMap((entry) => [
      { type: entry.author.type, ref: entry.author.ref },
      ...(entry.routes || []).map((route) => ({ type: route.targetType, ref: route.targetRef })),
      ...(entry.mentions || []).map((mention) => ({ type: mention.targetType, ref: mention.targetRef })),
    ]),
    ...(activities.data?.items || []).flatMap((entry) => [
      { type: entry.actor.type, ref: entry.actor.ref },
      { type: entry.objectType, ref: entry.objectRef },
    ]),
    ...(subscribers.data?.items || []).map((entry) => ({ type: entry.subscriberType, ref: entry.subscriberRef })),
    ...issueTasks.flatMap((task) => [
      { type: "agent", ref: task.agentId },
      { type: "team", ref: task.teamId },
      { type: task.originator.type, ref: task.originator.ref },
    ]),
  ]);
  const propertiesBusy = update.isPending || assign.isPending || action.isPending || comment.isPending || resolve.isPending || artifact.isPending || issue.isError;
  const dueValue = dueDraft ?? dateTimeLocal(item?.dueAt);
  const sourceUrl = sourceWebUrl(item?.sourceRef);
  const channelSource = item?.sourceType === 'channel' && item.sourceRef ? item.sourceRef.slice(0, item.sourceRef.lastIndexOf(':')) : '';
  const childItems = children.data?.items || [];
  const doneChildren = childItems.filter((childItem) => childItem.status === "done").length;
  const isSubscribed = !!username && !!subscribers.data?.items.some((subscriber) => subscriber.subscriberType === "human" && subscriber.subscriberRef === username);

  const timeline = useMemo(() => {
    const entries: Array<{ key: string; at: string; kind: "comment"; comment: Comment } | { key: string; at: string; kind: "activity"; activity: IssueActivity }> = [];
    for (const entry of comments.data?.items || []) {
      if (!entry.parentId) entries.push({ key: `comment-${entry.id}`, at: entry.createdAt, kind: "comment", comment: entry });
    }
    for (const entry of activities.data?.items || []) {
      if (!entry.action.startsWith("comment.")) entries.push({ key: `activity-${entry.id}`, at: entry.createdAt, kind: "activity", activity: entry });
    }
    return entries.sort((left, right) => new Date(left.at).getTime() - new Date(right.at).getTime());
  }, [activities.data?.items, comments.data?.items]);

  const repliesByRoot = useMemo(() => {
    const grouped = new Map<string, Comment[]>();
    for (const entry of comments.data?.items || []) {
      if (!entry.parentId) continue;
      const list = grouped.get(entry.threadRootId) || [];
      list.push(entry);
      grouped.set(entry.threadRootId, list);
    }
    return grouped;
  }, [comments.data?.items]);

  const mutationError = [update.error, comment.error, resolve.error, assign.error, child.error, artifact.error, action.error, subscription.error, download.error, artifactDownload.error].find(Boolean);

  if (issue.isLoading) {
    return <div className="flex min-h-[60vh] items-center justify-center gap-2 text-sm text-muted-foreground"><LoaderCircle className="h-4 w-4 animate-spin" /> Loading issue…</div>;
  }
  if (!item) {
    return <div className="mx-auto max-w-xl p-10"><div className="rounded-xl border border-red-200 bg-red-50 p-5 text-sm text-red-700">Unable to load this issue. {mutationMessage(issue.error)}</div></div>;
  }

  function submitComment(event: FormEvent) {
    event.preventDefault();
    if (content.trim()) comment.mutate({ text: content.trim() });
  }

  return (
    <div ref={container} className="min-h-full bg-white" onClickCapture={event => {
      if (!embedded || !onNavigateIssue || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
      const link = (event.target as HTMLElement).closest("a");
      if (!link) return;
      const match = new URL(link.href).pathname.match(/\/work\/issues\/([^/]+)$/);
      if (match) { event.preventDefault(); event.stopPropagation(); onNavigateIssue(decodeURIComponent(match[1])); }
    }}>
      <div className="sticky top-0 z-20 flex min-h-12 items-center justify-between gap-3 border-b border-slate-200 bg-white/95 px-4 backdrop-blur sm:px-6">
        <div className="flex min-w-0 items-center gap-2 text-sm">
          <Button variant="ghost" size="icon" className="h-8 w-8 shrink-0" title="Back to previous page" aria-label="Back to previous page" onClick={() => embedded ? onBack?.() : navigate(-1)}>
            <ArrowLeft className="h-4 w-4" />
          </Button>
          <span className="hidden text-muted-foreground sm:inline">Issues</span><span className="hidden text-slate-300 sm:inline">/</span>
          <span className="truncate font-medium">{item.identifier || item.id.slice(0, 8)}</span>
        </div>
        <div className="flex items-center gap-1">
          <Button variant="ghost" size="sm" className="h-8 gap-1.5 text-muted-foreground" disabled={!username || !subscribers.isSuccess || subscription.isPending} onClick={() => subscription.mutate(isSubscribed)} title={isSubscribed ? "Stop receiving updates" : "Receive issue updates"}><MessageSquare className="h-4 w-4" /><span>{subscribers.isSuccess ? subscribers.data.items.length : "…"}</span><span className="hidden sm:inline">{isSubscribed ? "Subscribed" : "Subscribe"}</span></Button>
          <Button variant="ghost" size="icon" className="h-8 w-8" title="Export issue" disabled={download.isPending} onClick={() => download.mutate()}><Download className="h-4 w-4" /></Button>
          {(item.status === "done" || item.status === "cancelled") && !item.archivedAt && <Button variant="ghost" size="icon" className="h-8 w-8" title="Archive issue" disabled={propertiesBusy} onClick={() => action.mutate("archive")}><Archive className="h-4 w-4" /></Button>}
          <Button variant={detailsOpen ? "secondary" : "ghost"} size="icon" className="h-8 w-8" title={detailsOpen ? "Hide properties" : "Show properties"} aria-controls="issue-properties" aria-expanded={detailsOpen} onClick={() => setDetailsOpen((value) => !value)}><PanelRight className="h-4 w-4" /></Button>
        </div>
      </div>

      {mutationError && <div role="alert" className="sticky top-12 z-40 flex items-start justify-between gap-2 border-b border-red-200 bg-red-50 px-6 py-2.5 text-sm text-red-700"><span>{mutationMessage(mutationError)}</span><button type="button" aria-label="Dismiss error" onClick={() => { update.reset(); comment.reset(); resolve.reset(); assign.reset(); child.reset(); artifact.reset(); action.reset(); subscription.reset(); download.reset(); artifactDownload.reset(); }}><X className="h-4 w-4" /></button></div>}
      {item.archivedAt && <div className="border-b bg-slate-100 px-6 py-2 text-sm text-muted-foreground">This issue is archived.</div>}

      <div className={cn("grid min-h-[calc(100vh-7rem)]", detailsOpen && !embedded && "xl:grid-cols-[minmax(0,1fr)_22rem]")}>
        <main className="min-w-0">
          <div className={cn("mx-auto max-w-4xl", embedded ? "px-5 py-6" : "px-5 py-10 sm:px-10 lg:py-14")}>
            <div className="group relative">
              {editingTitle ? (
                <div className="flex items-start gap-2">
                  <Input className="h-auto border-0 px-0 py-0 text-3xl font-bold tracking-tight shadow-none focus-visible:ring-0" value={titleDraft} onChange={(event) => setTitleDraft(event.target.value)} autoFocus onKeyDown={(event) => { if (event.key === "Enter" && titleDraft.trim()) update.mutate({ title: titleDraft.trim() }); if (event.key === "Escape") setEditingTitle(false); }} />
                  <Button size="icon" className="h-8 w-8" disabled={!titleDraft.trim() || update.isPending} onClick={() => update.mutate({ title: titleDraft.trim() })}><Check className="h-4 w-4" /></Button>
                  <Button variant="ghost" size="icon" className="h-8 w-8" onClick={() => { setTitleDraft(item.title); setEditingTitle(false); }}><X className="h-4 w-4" /></Button>
                </div>
              ) : (
                <div className="flex items-start gap-2"><h1 className="text-3xl font-bold leading-tight tracking-tight text-slate-950">{item.title}</h1><Button variant="ghost" size="icon" className="h-8 w-8 shrink-0 opacity-0 group-hover:opacity-100 focus:opacity-100" title="Edit title" onClick={() => setEditingTitle(true)}><Pencil className="h-4 w-4" /></Button></div>
              )}
            </div>

            <div className="mt-6 group">
              {editingDescription ? (
                <div className="space-y-2"><Textarea className="min-h-32 text-[15px] leading-7" value={descriptionDraft} onChange={(event) => setDescriptionDraft(event.target.value)} autoFocus placeholder="Add a description…" /><div className="flex gap-2"><Button size="sm" disabled={update.isPending} onClick={() => update.mutate({ description: descriptionDraft })}>Save</Button><Button size="sm" variant="ghost" onClick={() => { setDescriptionDraft(item.description || ""); setEditingDescription(false); }}>Cancel</Button></div></div>
              ) : (
                <button type="button" className="flex w-full items-start gap-2 rounded-lg text-left text-[15px] leading-7 text-slate-700 hover:bg-slate-50" onClick={() => setEditingDescription(true)}><div className={cn("md-text min-w-0 flex-1", !item.description && "italic text-muted-foreground")}>{item.description ? <ReactMarkdown>{item.description}</ReactMarkdown> : "Add a description…"}</div><Pencil className="mt-1.5 h-3.5 w-3.5 shrink-0 text-slate-400 opacity-0 group-hover:opacity-100" /></button>
              )}
            </div>

            <section className="mt-8 border-b border-slate-200 pb-8">
              <div className="flex items-center justify-between gap-3">
                <button type="button" className="flex min-w-0 items-center gap-2 text-sm font-medium text-slate-700" onClick={() => setChildOpen((value) => !value)}>{childOpen || childItems.length ? <ChevronDown className="h-4 w-4 text-slate-400" /> : <ChevronRight className="h-4 w-4 text-slate-400" />}<ListChecks className="h-4 w-4 text-slate-400" /><span>Sub-issues</span>{!!childItems.length && <span className="text-xs font-normal text-muted-foreground">{doneChildren}/{childItems.length}</span>}</button>
                <Button variant="ghost" size="sm" className="h-8" onClick={() => setChildOpen(true)}><Plus className="h-4 w-4" /> Add</Button>
              </div>
              {(childOpen || !!childItems.length) && <div className="mt-3 space-y-2 pl-6">{childItems.map((childItem) => <Link key={childItem.id} to={scope.scopedPath(`/work/issues/${childItem.id}`)} className="flex items-center gap-3 rounded-lg px-2 py-2 text-sm hover:bg-slate-50">{childItem.status === "done" ? <CheckCircle2 className="h-4 w-4 text-emerald-600" /> : <Circle className="h-4 w-4 text-slate-400" />}<span className={cn("min-w-0 flex-1 truncate", childItem.status === "done" && "text-muted-foreground line-through")}>{childItem.title}</span><span className="text-xs text-muted-foreground">{STATUS_LABELS[childItem.status] || childItem.status}</span></Link>)}<div className="flex gap-2"><Input value={childTitle} onChange={(event) => setChildTitle(event.target.value)} placeholder="What needs to be done?" onKeyDown={(event) => { if (event.key === "Enter" && childTitle.trim()) child.mutate(); }} /><Button variant="outline" disabled={!childTitle.trim() || child.isPending} onClick={() => child.mutate()}>Create</Button></div></div>}
            </section>

            <section className="pt-9">
              <div className="flex items-center justify-between gap-4"><h2 className="text-xl font-bold text-slate-900">Activity</h2><div className="flex items-center gap-2 text-sm text-muted-foreground"><MessageSquare className="h-4 w-4" />{summary.data?.summary.commentCount || 0}</div></div>
              <div className="mt-6 space-y-4">
                {!timeline.length && !comments.isLoading && <div className="rounded-xl border border-dashed py-10 text-center text-sm text-muted-foreground">No activity yet. Start the conversation below.</div>}
                {timeline.map((timelineEntry) => {
                  if (timelineEntry.kind === "activity") return <div key={timelineEntry.key} className="flex items-start gap-3 px-3 py-1 text-sm text-slate-600"><span className="mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-slate-100"><Clock3 className="h-3.5 w-3.5 text-slate-500" /></span><div className="min-w-0 flex-1 pt-1">{activityDescription(timelineEntry.activity, identities)}</div><span className="shrink-0 pt-1 text-xs text-muted-foreground">{formatRelative(timelineEntry.activity.createdAt)}</span></div>;
                  const entry = timelineEntry.comment;
                  const replies = repliesByRoot.get(entry.threadRootId) || [];
                  return (
                    <article id={`comment-${entry.id}`} key={timelineEntry.key} className={cn(focusCommentId === entry.id && "ring-2 ring-indigo-400", "overflow-hidden rounded-2xl border bg-white shadow-sm", entry.resolvedAt ? "border-emerald-200" : "border-slate-200")}>
                      <div className="flex items-start gap-3 p-4 sm:p-5"><ActorAvatar actor={entry.author} identities={identities} /><CommentBody entry={entry} identities={identities} /></div>
                      {!!replies.length && <div className="border-t border-slate-100 bg-slate-50/60 px-4 py-2 sm:px-5">{replies.map((reply) => <div id={`comment-${reply.id}`} key={reply.id} className={cn("flex items-start gap-3 border-b border-slate-100 py-3 last:border-0", focusCommentId === reply.id && "rounded-lg bg-indigo-50 ring-2 ring-indigo-400")}><ActorAvatar actor={reply.author} identities={identities} size="sm" /><CommentBody entry={reply} identities={identities} /></div>)}</div>}
                      {!entry.deletedAt && <div className="flex items-center gap-1 border-t border-slate-100 px-3 py-2"><Button variant="ghost" size="sm" className="h-8 text-muted-foreground" onClick={() => { setReplyingTo(entry.id); setReplyContent(""); }}><Reply className="h-3.5 w-3.5" /> Reply</Button><Button variant="ghost" size="sm" className="h-8 text-muted-foreground" disabled={resolve.isPending} onClick={() => resolve.mutate({ id: entry.id, version: entry.version, resolved: !entry.resolvedAt })}>{entry.resolvedAt ? "Reopen" : "Resolve"}</Button></div>}
                      {replyingTo === entry.id && <div className="flex gap-2 border-t border-slate-100 bg-slate-50 p-3"><Textarea className="min-h-16 bg-white" value={replyContent} onChange={(event) => setReplyContent(event.target.value)} placeholder={`Reply to ${actorName(entry.author, identities)}…`} autoFocus /><Button size="icon" className="h-9 w-9 self-end rounded-full" disabled={!replyContent.trim() || comment.isPending} onClick={() => comment.mutate({ text: replyContent.trim(), parentId: entry.id })}><ArrowUp className="h-4 w-4" /></Button></div>}
                    </article>
                  );
                })}
              </div>

              <form onSubmit={submitComment} className="mt-6 overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm focus-within:border-indigo-300 focus-within:ring-4 focus-within:ring-indigo-50">
                <Textarea value={content} onChange={(event) => setContent(event.target.value)} onKeyDown={(event) => { if ((event.metaKey || event.ctrlKey) && event.key === "Enter" && content.trim()) { event.preventDefault(); comment.mutate({ text: content.trim() }); } }} className="min-h-28 resize-y border-0 px-4 py-4 text-[15px] shadow-none focus-visible:ring-0" placeholder="Leave a comment…" />
                {mentionOpen && <div className="grid gap-2 border-t border-slate-100 bg-slate-50 px-3 py-3 sm:grid-cols-[8rem_1fr]"><select className="h-9 rounded-lg border border-border bg-white px-3 text-sm" value={mentionType} onChange={(event) => { setMentionType(event.target.value); setMentionRef(""); }}><option value="agent">Agent</option><option value="team">Team</option><option value="human">Human</option></select>{mentionType === "agent" ? <AgentPicker value={mentionRef} onChange={setMentionRef} emptyLabel="Mention an Agent…" aria-label="Mentioned Agent" /> : <Input className="h-9" value={mentionRef} onChange={(event) => setMentionRef(event.target.value)} placeholder={`Mention a ${mentionType}…`} />}</div>}
                {artifactFile && <div className="flex items-center justify-between gap-3 border-t border-slate-100 bg-slate-50 px-4 py-2 text-sm"><span className="min-w-0 truncate"><Paperclip className="mr-2 inline h-3.5 w-3.5" />{artifactFile.name}</span><div className="flex gap-1"><Button type="button" variant="ghost" size="sm" disabled={artifact.isPending} onClick={() => artifact.mutate()}>{artifact.isPending ? "Uploading…" : "Upload"}</Button><Button type="button" variant="ghost" size="icon" className="h-8 w-8" aria-label="Remove pending attachment" onClick={() => setArtifactFile(null)}><X className="h-3.5 w-3.5" /></Button></div></div>}
                <div className="flex items-center justify-between border-t border-slate-100 px-3 py-2"><div className="flex items-center gap-1"><select aria-label="Comment type" className="h-8 rounded-lg border-0 bg-transparent px-2 text-xs" value={commentType} onChange={event => setCommentType(event.target.value)}><option value="comment">Comment</option><option value="result">Result</option></select><Button type="button" variant={mentionOpen ? "secondary" : "ghost"} size="sm" className="h-8" onClick={() => setMentionOpen((value) => !value)} title="Mention someone"><span className="text-base leading-none">@</span><span className="hidden sm:inline">Mention</span></Button><label className="inline-flex h-8 cursor-pointer items-center gap-2 rounded-lg px-3 text-sm font-medium text-muted-foreground hover:bg-muted"><Paperclip className="h-4 w-4" /><span className="hidden sm:inline">Attach</span><input type="file" className="sr-only" onChange={event => { setArtifactFile(event.target.files?.[0] || null); event.target.value = ""; }} /></label></div><Button type="submit" size="icon" className="h-9 w-9 rounded-full" disabled={!content.trim() || comment.isPending} title="Send comment (⌘ Enter)">{comment.isPending ? <LoaderCircle className="h-4 w-4 animate-spin" /> : <ArrowUp className="h-4 w-4" />}</Button></div>
              </form>
            </section>
          </div>
        </main>

        {detailsOpen && <button type="button" aria-label="Close properties" className={cn("fixed inset-0 top-14 z-20 bg-slate-950/20", !embedded && "xl:hidden")} onClick={() => setDetailsOpen(false)} />}
        {detailsOpen && (
          <aside id="issue-properties" className={cn("fixed inset-y-14 right-0 z-30 w-[22rem] max-w-[calc(100vw-1rem)] overflow-y-auto border-l border-slate-200 bg-slate-50 shadow-2xl", !embedded && "xl:static xl:z-auto xl:w-auto xl:max-w-none xl:overflow-visible xl:border-t-0 xl:shadow-none")}>
            <div className="space-y-7 px-5 py-7 xl:sticky xl:top-12 xl:max-h-[calc(100vh-7rem)] xl:overflow-y-auto">
              {[issue, subscribers, artifacts, tasks, runs].some(query => query.isError) && <div role="alert" className="rounded-lg border border-red-200 bg-red-50 p-3 text-xs text-red-700">Some issue details could not be loaded. <button className="underline" onClick={() => { void issue.refetch(); void subscribers.refetch(); void artifacts.refetch(); void tasks.refetch(); void runs.refetch(); }}>Retry</button></div>}
              <section>
                <div className="mb-2 flex items-center justify-between"><h2><button type="button" className="flex items-center gap-2 text-sm font-semibold text-slate-900" aria-expanded={propertiesOpen} aria-controls="issue-property-fields" onClick={() => setPropertiesOpen(value => !value)}>Properties <ChevronDown className={cn("h-4 w-4 text-slate-400 transition-transform", !propertiesOpen && "-rotate-90")} /></button></h2><Button variant="ghost" size="icon" className={cn("h-8 w-8", !embedded && "xl:hidden")} aria-label="Close properties" onClick={() => setDetailsOpen(false)}><X className="h-4 w-4" /></Button></div>
                {propertiesOpen && <div id="issue-property-fields">
                <PropertyRow icon={CircleDot} label="Status"><select aria-label="Issue status" className={cn("h-8 w-full rounded-lg border border-transparent bg-transparent px-2 text-sm font-medium hover:border-border hover:bg-white", item.status === "done" && "text-emerald-700", item.status === "blocked" && "text-red-700")} value={item.status} disabled={propertiesBusy || !!item.archivedAt} onChange={(event) => action.mutate(event.target.value)}>{(STATUS_OPTIONS[item.status] || [item.status]).map((status) => <option key={status} value={status}>{STATUS_LABELS[status] || status}</option>)}</select></PropertyRow>
                <PropertyRow icon={item.assigneeType === "team" ? UsersRound : item.assigneeType === "agent" ? Bot : UserRound} label="Assignee">
                  {!assigneeOpen ? <button type="button" aria-label="Edit assignee" disabled={propertiesBusy || !!item.archivedAt} className="min-h-8 w-full rounded-lg px-2 text-left text-sm hover:bg-white" onClick={() => { setAssigneeType(item.assigneeType || "agent"); setAssigneeRef(item.assigneeRef || ""); setAssigneeOpen(true); }}>{item.assigneeRef ? <EntityIdentityText identities={identities} type={item.assigneeType} entityRef={item.assigneeRef} secondary /> : <span className="text-muted-foreground">Unassigned</span>}</button> : <div className="space-y-2 rounded-lg border bg-white p-2">
                    <select aria-label="Assignee type" className="h-8 w-full rounded-md border px-2 text-xs" value={assigneeType} disabled={assign.isPending} onChange={event => { setAssigneeType(event.target.value); setAssigneeRef(""); }}><option value="">Unassigned</option><option value="agent">Agent</option><option value="team">Team</option><option value="human">Human</option></select>
                    {assigneeType === "agent" ? <AgentPicker value={assigneeRef} onChange={setAssigneeRef} disabled={assign.isPending} emptyLabel="Select Agent…" aria-label="Issue assignee Agent" className="[&_[role=listbox]]:right-0" /> : assigneeType === "team" ? <NamedResourcePicker value={assigneeRef} onChange={setAssigneeRef} options={teamOptions} resourceLabel="Team" className="[&_[role=listbox]]:right-0" loading={teams.isLoading || assign.isPending} error={teams.isError} aria-label="Issue assignee Team" /> : assigneeType === "human" ? <><Input aria-label="Assignee username" className="h-8 text-xs" value={assigneeRef} onChange={event => setAssigneeRef(event.target.value)} placeholder="Username" /><Button size="sm" variant="ghost" className="h-7 px-1 text-xs" onClick={() => setAssigneeRef(username)}>Assign to me</Button></> : null}
                    <p className="text-xs leading-5 text-muted-foreground">{["agent", "team"].includes(assigneeType) ? (["done", "cancelled"].includes(item.status) ? "Reopen the issue before starting another execution." : "Assigning starts a new execution. Existing executions continue.") : "Changes responsibility. Existing executions continue."}</p>
                    <div className="flex gap-1"><Button size="sm" className="h-8" disabled={propertiesBusy || (!!assigneeType && !assigneeRef.trim()) || (assigneeType === (item.assigneeType || "") && assigneeRef.trim() === (item.assigneeRef || "")) || (["agent", "team"].includes(assigneeType) && ["done", "cancelled"].includes(item.status))} onClick={() => assign.mutate()}>Assign</Button><Button size="sm" variant="ghost" className="h-8" onClick={() => setAssigneeOpen(false)}>Cancel</Button></div>
                  </div>}
                </PropertyRow>
                <PropertyRow icon={ClipboardList} label="Priority"><select aria-label="Issue priority" className="h-8 w-full rounded-lg border border-transparent bg-transparent px-2 text-sm capitalize hover:border-border hover:bg-white" value={item.priority || "none"} disabled={propertiesBusy} onChange={(event) => update.mutate({ priority: event.target.value })}><option value="none">No priority</option><option value="low">Low</option><option value="normal">Normal</option><option value="high">High</option><option value="urgent">Urgent</option></select></PropertyRow>
                <PropertyRow icon={CalendarDays} label="Due date"><div className="space-y-1">
                  <input aria-label="Issue due date" type="datetime-local" className="h-8 w-full min-w-0 rounded-lg border border-transparent bg-transparent px-2 text-xs hover:border-border hover:bg-white" value={dueValue} onChange={event => setDueDraft(event.target.value)} />
                  <div className="flex gap-1"><Button variant="ghost" size="sm" className="h-7 px-2 text-xs" disabled={dueValue === dateTimeLocal(item.dueAt) || propertiesBusy} aria-label="Save due date" onClick={() => update.mutate(dueDatePatch(dueValue))}><Check className="h-3.5 w-3.5" /> Save</Button>{item.dueAt && <Button variant="ghost" size="sm" className="h-7 px-2 text-xs" disabled={propertiesBusy} aria-label="Clear due date" onClick={() => update.mutate(dueDatePatch(""))}><X className="h-3.5 w-3.5" /> Clear</Button>}</div>
                </div></PropertyRow>
                {item.executionTargetRef && <PropertyRow icon={Sparkles} label="Exec target"><div className="px-2 py-1 text-sm"><EntityIdentityText identities={identities} type={item.executionTargetType} entityRef={item.executionTargetRef} secondary /></div></PropertyRow>}
                </div>}
              </section>

              <IssueSharing issue={item} />
              <IssueAcceptanceEditor value={item.acceptanceCriteria} busy={propertiesBusy} onSave={acceptanceCriteria => update.mutateAsync({ acceptanceCriteria })} />

              <section className="border-t border-slate-200 pt-5"><h2 className="mb-3 flex items-center gap-2 text-sm font-semibold text-slate-900"><GitPullRequest className="h-4 w-4" /> Source</h2>{item.sourceRef ? <div className="rounded-lg bg-white p-3 text-sm"><div className="text-xs font-medium capitalize text-muted-foreground">{item.sourceType?.replace(/_/g, " ") || "External work"}</div><EntityIdentityText identities={identities} type={item.sourceType} entityRef={item.sourceRef} secondary className="mt-1 flex" />{channelSource && <Link className="mt-2 block text-xs text-primary" to={scope.scopedPath(`/agent-center/entrypoints/${encodeURIComponent(channelSource)}`)}>Channel 接待与工作回传</Link>}{sourceUrl && <a href={sourceUrl} target="_blank" rel="noopener noreferrer" className="mt-2 inline-flex items-center gap-1 text-xs text-primary">Open source<ExternalLink className="h-3 w-3" /></a>}</div> : <p className="text-xs leading-5 text-muted-foreground">Created in this workspace. No external source is linked.</p>}</section>

              <details className="group border-t border-slate-200 pt-5" open><summary className="flex cursor-pointer list-none items-center justify-between text-sm font-semibold text-slate-900"><span className="flex items-center gap-2"><Clock3 className="h-4 w-4" /> Executions</span><span className="text-xs font-normal text-muted-foreground">{issueExecutions.length} execution{issueExecutions.length === 1 ? "" : "s"}</span></summary><div className="mt-3 space-y-2">{issueExecutions.map(({ run, tasks: runTasks, toolFailures: failures, dispatchDeferrals: deferrals }, index) => <Link key={run.id} to={scope.scopedPath(`/work/executions/${run.id}`)} className={cn("block rounded-lg bg-white p-3 text-xs hover:ring-1", failures.length ? "ring-1 ring-red-200 hover:ring-red-300" : deferrals.length ? "ring-1 ring-amber-200 hover:ring-amber-300" : "hover:ring-slate-200")}><span className="flex items-center justify-between gap-2"><span className="min-w-0"><span className="block truncate font-medium">Execution #{issueExecutions.length - index}</span><span className="text-muted-foreground">{run.mode} · {runTasks.length} step{runTasks.length === 1 ? "" : "s"} in this issue · {formatRelative(run.createdAt)}</span></span><span className="flex shrink-0 flex-col items-end gap-1">{failures.length > 0 && <Badge tone="danger">{failures.length} tool failure{failures.length === 1 ? "" : "s"}</Badge>}{deferrals.length > 0 && <Badge tone="warning">dispatch deferred</Badge>}<Badge tone={run.state === "succeeded" ? "success" : run.state === "partial_succeeded" ? "warning" : run.state === "failed" || run.state === "cancelled" ? "danger" : "info"}>{run.state.replace(/_/g, " ")}</Badge></span></span>{failures.length > 0 && <span className="mt-2 block truncate text-red-700" title={toolFailureMessage(failures[0])}>First failure: {toolFailureMessage(failures[0])}</span>}{deferrals.length > 0 && <span className="mt-2 block truncate text-amber-700" title={dispatchDeferredMessage(deferrals[0])}>Dispatch delayed: {dispatchDeferredMessage(deferrals[0])}</span>}{!!runTasks.length && <span className="mt-2 flex flex-wrap gap-1.5">{runTasks.slice(0, 3).map((task) => <span key={task.id} className="rounded-full bg-slate-100 px-2 py-1 text-[11px] text-slate-600"><EntityIdentityText identities={identities} type="agent" entityRef={task.agentId} /> · {task.status}</span>)}{runTasks.length > 3 && <span className="px-1 py-1 text-[11px] text-muted-foreground">+{runTasks.length - 3} more</span>}</span>}</Link>)}{runs.isSuccess && !issueExecutions.length && <p className="text-xs text-muted-foreground">No execution has started yet.</p>}</div></details>

              <details className="group border-t border-slate-200 pt-5" open><summary className="flex cursor-pointer list-none items-center justify-between text-sm font-semibold text-slate-900"><span className="flex items-center gap-2"><Paperclip className="h-4 w-4" /> Artifacts</span><span className="text-xs font-normal text-muted-foreground">{artifacts.isSuccess ? artifacts.data.items.length : "…"}</span></summary><div className="mt-3 space-y-2">{(artifacts.data?.items || []).map((entry) => <div key={entry.id} className="flex items-center gap-2 rounded-lg bg-white p-2.5 text-xs"><span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-md bg-slate-100"><File className="h-4 w-4 text-slate-500" /></span><span className="min-w-0 flex-1"><span className="block truncate font-medium" title={entry.filename}>{entry.filename}</span><span className="text-muted-foreground">{formatBytes(entry.sizeBytes)} · {formatRelative(entry.createdAt)}</span></span><Button variant="ghost" size="icon" className="h-8 w-8 shrink-0" aria-label={`Download ${entry.filename}`} disabled={artifactDownload.isPending} onClick={() => artifactDownload.mutate(entry)}><Download className="h-4 w-4" /></Button></div>)}{artifacts.isSuccess && !artifacts.data?.items.length && <p className="text-xs text-muted-foreground">No files attached.</p>}</div></details>

              <section className="border-t border-slate-200 pt-5"><h2 className="mb-3 text-sm font-semibold text-slate-900">Details</h2><dl className="grid grid-cols-[5rem_minmax(0,1fr)] gap-x-3 gap-y-3 text-xs"><dt className="text-muted-foreground">Created by</dt><dd className="truncate">{actorName(item.creator, identities)}</dd><dt className="text-muted-foreground">Created</dt><dd>{new Date(item.createdAt).toLocaleString()}</dd><dt className="text-muted-foreground">Updated</dt><dd>{new Date(item.updatedAt).toLocaleString()}</dd><dt className="text-muted-foreground">Policy</dt><dd className="capitalize">{item.completionPolicy?.replace(/_/g, " ") || "review"}</dd><dt className="text-muted-foreground">Issue ID</dt><dd className="truncate font-mono" title={item.id}>{item.id}</dd></dl>{item.parentIssueId && <Button asChild variant="link" size="sm" className="mt-3 h-auto p-0"><Link to={scope.scopedPath(`/work/issues/${item.parentIssueId}`)}>Open parent issue</Link></Button>}</section>
            </div>
          </aside>
        )}
      </div>
    </div>
  );
}
