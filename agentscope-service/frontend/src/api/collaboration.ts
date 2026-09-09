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

import { api, apiFetch, apiResponse } from "@/lib/apiClient";

export interface Actor {
  type: "human" | "agent" | "system" | "automation";
  ref?: string;
}
export interface Issue {
  access?: import("./permissions").IssueAccess;
  id: string;
  identifier?: string;
  tenant: string;
  namespace: string;
  title: string;
  description?: string;
  status: string;
  priority: string;
  kind: 'user_work' | 'endpoint_job' | 'automation_job' | string;
  visibility: 'work_hub' | 'operational' | string;
  completionPolicy: 'review' | 'automatic' | 'external' | string;
  assigneeType?: string;
  assigneeRef?: string;
  executionTargetType?: "agent" | "team" | "orchestration_revision" | string;
  executionTargetRef?: string;
  creator: Actor;
  parentIssueId?: string;
  acceptanceCriteria?: unknown;
  contextRefs?: unknown;
  sourceType?: string;
  sourceRef?: string;
  dueAt?: string;
  version: number;
  createdAt: string;
  updatedAt: string;
  resolvedAt?: string;
  archivedAt?: string;
}
export interface IssueSummary {
  issueId: string;
  title: string;
  status: string;
  commentCount: number;
  unresolvedThreads: number;
  activeTasks: number;
  terminalTasks: number;
  childCount: number;
  latestResult?: string;
  updatedAt: string;
}
export interface Comment {
  id: string;
  issueId: string;
  parentId?: string;
  threadRootId: string;
  author: Actor;
  content: string;
  type: string;
  sourceTaskId?: string;
  version: number;
  createdAt: string;
  updatedAt: string;
  resolvedAt?: string;
  deletedAt?: string;
  mentions?: Array<{ targetType: string; targetRef: string }>;
  routes?: Array<{
    targetType: string;
    targetRef: string;
    outcome: string;
    reasonCode?: string;
    taskId?: string;
  }>;
  externalSync?: {
    syncState: string;
    lastError?: string;
    attempts: number;
    externalId?: string;
  };
}
export interface IssueActivity {
  id: string;
  issueId?: string;
  actor: Actor;
  action: string;
  objectType: string;
  objectRef: string;
  causationId?: string;
  correlationId?: string;
  details?: Record<string, unknown>;
  createdAt: string;
}
export interface IssueSubscriber {
  issueId: string;
  subscriberType: string;
  subscriberRef: string;
  createdAt: string;
}
export interface Artifact {
  id: string;
  filename: string;
  contentType: string;
  sizeBytes: number;
  checksum: string;
  uploader: Actor;
  createdAt: string;
  expiresAt?: string;
}
export interface TaskInput {
  id: string;
  commentId: string;
  commentVersion: number;
  sequence: number;
  state: string;
  attempts: number;
}
export interface AgentTask {
  id: string;
  tenant: string;
  namespace: string;
  issueId: string;
  orchestrationRunId: string;
  runNodeId: string;
  currentAttemptId?: string;
  agentId: string;
  status: string;
  priority: number;
  triggerType: string;
  retryOfTaskId?: string;
  rerunOfTaskId?: string;
  teamId?: string;
  teamRole?: string;
  leaderTask?: boolean;
  originator: Actor;
  runtimeBinding?: unknown;
  sessionId?: string;
  result?: unknown;
  errorCode?: string;
  errorMessage?: string;
  version: number;
  createdAt: string;
  dispatchedAt?: string;
  startedAt?: string;
  completedAt?: string;
  inputs?: TaskInput[];
}
export interface RuntimeBinding {
  agentId: string;
  bindingId: string;
  kind: "managed" | "external-application" | "hosted-runtime";
  ownerRef?: string;
  managedDefinitionRef?: string;
  instanceSelector?: Record<string, string>;
  runtimeProfileId?: string;
  runtimePoolId?: string;
}
export interface RuntimeBindingCandidate {
  binding: RuntimeBinding;
  requiredCapabilities?: Record<string, unknown>;
  securityConstraints?: Record<string, unknown>;
}
export interface RuntimeBindingPolicy {
  candidates: RuntimeBindingCandidate[];
  selectionMode: "ordered";
  fallbackMode: "disabled" | "fresh";
  retryPolicy?: Record<string, unknown>;
}
export interface TeamMember {
  id: string;
  teamId: string;
  agentId: string;
  role: string;
  instructions?: string;
  capabilityRequirements?: Record<string, unknown>;
  runtimeBindingPolicy?: RuntimeBindingPolicy | null;
}
export interface Team {
  id: string;
  tenant: string;
  namespace: string;
  name: string;
  description?: string;
  instructions?: string;
  status: 'active' | 'disabled' | string;
  leaderAgentId: string;
  policy?: unknown;
  version: number;
  createdAt: string;
  updatedAt: string;
  members?: TeamMember[];
}
export interface TeamOverview {
  teamId: string;
  observedAt: string;
  readiness: 'ready' | 'degraded' | 'unavailable' | string;
  reason: string;
  members: Array<{
    agentId: string;
    role: string;
    leader: boolean;
    lifecycle?: string;
    readiness: { state: string; mode?: string; reason: string; activeBindingId?: string };
  }>;
  runs: { total: number; activeTasks: number };
  endpoints: { total: number; published: number };
}
export interface InboxItem {
  id: string;
  type: string;
  severity: string;
  issueId?: string;
  commentId?: string;
  approvalId?: string;
  actor: Actor;
  title: string;
  body?: string;
  details?: Record<string, unknown>;
  needsAction: boolean;
  readAt?: string;
  resolvedAt?: string;
  read: boolean;
  archived: boolean;
  createdAt: string;
}
export interface Approval {
  id: string;
  targetType: string;
  targetRef: string;
  issueId?: string;
  runId?: string;
  runNodeId?: string;
  requestedBy: Actor;
  approverRef: string;
  status: string;
  reason?: string;
  request?: unknown;
  decision?: unknown;
  version: number;
  createdAt: string;
  updatedAt: string;
}
export type { Automation } from './automations';
export { listAutomations, createAutomation, updateAutomation, triggerAutomation } from './automations';

function query(values: Record<string, string | number | undefined>) {
  const p = new URLSearchParams();
  Object.entries(values).forEach(([k, v]) => {
    if (v !== undefined && v !== "") p.set(k, String(v));
  });
  const s = p.toString();
  return s ? `?${s}` : "";
}
export const listIssues = (
  tenant: string,
  namespace: string,
  status = "",
  search = "",
  archived = false,
  filters: {
    kind?: string;
    visibility?: string;
    includeOperational?: boolean;
  } = {},
) =>
  api.get<{ items: Issue[]; nextCursor?: string }>(
    `/api/v1/issues${query({
      tenant,
      namespace,
      status,
      search,
      archived: archived ? "true" : undefined,
      kind: filters.kind,
      visibility: filters.visibility,
      includeOperational: filters.includeOperational ? "true" : undefined,
    })}`,
  );
export const getIssue = (id: string) =>
  api.get<{ issue: Issue }>(`/api/v1/issues/${encodeURIComponent(id)}`);
export const createIssue = (body: unknown) =>
  api.post<{ issue: Issue; agentTask?: AgentTask }>("/api/v1/issues", body);
export const updateIssue = (id: string, body: unknown) =>
  api.patch<{ issue: Issue }>(`/api/v1/issues/${encodeURIComponent(id)}`, body);
export const transitionIssue = (
  id: string,
  status: string,
  expectedVersion: number,
  reason = "",
) =>
  api.post<{ issue: Issue }>(
    `/api/v1/issues/${encodeURIComponent(id)}/transition`,
    { status, expectedVersion, reason },
  );
export const acceptIssue = (id: string, expectedVersion: number) =>
  api.post<{ issue: Issue }>(
    `/api/v1/issues/${encodeURIComponent(id)}/accept`,
    { expectedVersion },
  );
export const rejectIssue = (
  id: string,
  expectedVersion: number,
  reason: string,
) =>
  api.post<{ issue: Issue }>(
    `/api/v1/issues/${encodeURIComponent(id)}/reject`,
    { expectedVersion, reason },
  );
export const reopenIssue = (id: string, expectedVersion: number, reason = "") =>
  api.post<{ issue: Issue }>(
    `/api/v1/issues/${encodeURIComponent(id)}/reopen`,
    { expectedVersion, reason },
  );
export const archiveIssue = (id: string, expectedVersion: number) =>
  api.post<{ issue: Issue }>(
    `/api/v1/issues/${encodeURIComponent(id)}/archive`,
    { expectedVersion },
  );
export const getIssueSummary = (id: string) =>
  api.get<{ summary: IssueSummary }>(
    `/api/v1/issues/${encodeURIComponent(id)}/summary`,
  );
export const exportIssue = (id: string) =>
  api.get<Record<string, unknown>>(
    `/api/v1/issues/${encodeURIComponent(id)}/export`,
  );
export const listComments = async (issueId: string, focusCommentId?: string) => {
  const items: Comment[] = [];
  let cursor = "";
  do {
    const response = await api.get<{
      items: Comment[];
      nextCursor?: string;
      externalSync?: Record<string, NonNullable<Comment["externalSync"]>>;
    }>(`/api/v1/issues/${encodeURIComponent(issueId)}/comments${query({ cursor: cursor || undefined })}`);
    items.push(...response.items.map(comment => ({ ...comment, externalSync: response.externalSync?.[comment.id] })));
    const next = response.nextCursor || "";
    if (!focusCommentId || items.some(comment => comment.id === focusCommentId) || !next || next === cursor) break;
    cursor = next;
  } while (cursor);
  return { items };
};
export const listIssueActivity = (issueId: string) =>
  api.get<{ items: IssueActivity[] }>(
    `/api/v1/issues/${encodeURIComponent(issueId)}/activity`,
  );
export const listIssueSubscribers = (issueId: string) =>
  api.get<{ items: IssueSubscriber[] }>(
    `/api/v1/issues/${encodeURIComponent(issueId)}/subscribers`,
  );
export const subscribeIssue = (issueId: string) =>
  api.post<{ subscriber: IssueSubscriber }>(
    `/api/v1/issues/${encodeURIComponent(issueId)}/subscribers`,
    { type: "human" },
  );
export const unsubscribeIssue = (
  issueId: string,
  subscriberType: string,
  subscriberRef: string,
) =>
  api.delete<void>(
    `/api/v1/issues/${encodeURIComponent(issueId)}/subscribers/${encodeURIComponent(subscriberType)}/${encodeURIComponent(subscriberRef)}`,
  );
export const listIssueArtifacts = (issueId: string) =>
  api.get<{ items: Artifact[] }>(
    `/api/v1/issues/${encodeURIComponent(issueId)}/artifacts`,
  );
export async function downloadIssueArtifact(artifact: Pick<Artifact, "id" | "filename">): Promise<Blob> {
  const response = await apiResponse(`/api/v1/artifacts/${encodeURIComponent(artifact.id)}/download`, { method: "POST" });
  return response.blob();
}
export const addComment = (
  issueId: string,
  content: string,
  parentId?: string,
  mentions: Array<{ type: string; ref: string }> = [],
  type = "comment",
) =>
  api.post<Comment>(`/api/v1/issues/${encodeURIComponent(issueId)}/comments`, {
    content,
    parentId,
    mentions,
    type,
  });
export const resolveComment = (
  issueId: string,
  commentId: string,
  expectedVersion: number,
  resolved = true,
) =>
  api.post<{ comment: Comment }>(
    `/api/v1/issues/${encodeURIComponent(issueId)}/comments/${encodeURIComponent(commentId)}/resolve`,
    { expectedVersion, resolved },
  );
export const assignIssue = (
  id: string,
  assigneeType: string,
  assigneeRef: string,
  expectedVersion: number,
) =>
  api.post<{ issue: Issue; agentTask?: AgentTask }>(
    `/api/v1/issues/${encodeURIComponent(id)}/assign`,
    { assigneeType, assigneeRef, expectedVersion },
  );
export const createChildIssue = (id: string, body: unknown) =>
  api.post<{ issue: Issue; agentTask?: AgentTask }>(
    `/api/v1/issues/${encodeURIComponent(id)}/children`,
    body,
  );
export const listChildIssues = (
  tenant: string,
  namespace: string,
  parentIssueId: string,
) =>
  api.get<{ items: Issue[] }>(
    `/api/v1/issues${query({ tenant, namespace, parentIssueId })}`,
  );
export const uploadIssueArtifact = async (
  tenant: string,
  namespace: string,
  issueId: string,
  file: File,
) => {
  const form = new FormData();
  form.set("tenant", tenant);
  form.set("namespace", namespace);
  form.set("targetType", "issue");
  form.set("targetRef", issueId);
  form.set("relation", "attachment");
  form.set("file", file);
  return apiFetch<{
    artifact: {
      id: string;
      filename: string;
      sizeBytes: number;
      contentType: string;
    };
  }>("/api/v1/artifacts/uploads", { method: "POST", body: form });
};
export const listTasks = (
  tenant: string,
  namespace: string,
  status = "",
  agentId = "",
) =>
  api.get<{ items: AgentTask[] }>(
    `/api/v1/agent-tasks${query({ tenant, namespace, status, agentId })}`,
  );
export async function listIssueTasks(tenant: string, namespace: string, issueId: string) {
  const items: AgentTask[] = [];
  for (let offset = 0; ; offset += 100) {
    const page = await api.get<{ items: AgentTask[] }>(
      `/api/v1/agent-tasks${query({ tenant, namespace, issueId, limit: 100, offset })}`,
    );
    items.push(...page.items);
    if (page.items.length < 100) return { items };
  }
}
export const listTeamTasks = (tenant: string, namespace: string, teamId: string) =>
  api.get<{ items: AgentTask[] }>(
    `/api/v1/agent-tasks${query({ tenant, namespace, teamId })}`,
  );
export const getTask = (id: string) =>
  api.get<{ task: AgentTask; inputSummaries?: Array<{ inputId: string; commentId: string; version: number; state: string; content?: string }> }>(`/api/v1/agent-tasks/${encodeURIComponent(id)}`);
export const cancelTask = (id: string, expectedVersion: number) =>
  api.post<{ task: AgentTask }>(
    `/api/v1/agent-tasks/${encodeURIComponent(id)}/cancel`,
    { expectedVersion },
  );
export const retryTask = (id: string) =>
  api.post<{ task: AgentTask }>(
    `/api/v1/agent-tasks/${encodeURIComponent(id)}/retry`,
    {},
  );
export const listTeams = (tenant: string, namespace: string) =>
  api.get<{ items: Team[] }>(`/api/v1/teams${query({ tenant, namespace })}`);
export const getTeam = (id: string) =>
  api.get<{ team: Team }>(`/api/v1/teams/${encodeURIComponent(id)}`);
export const getTeamOverview = (id: string) =>
  api.get<{ team: Team; overview: TeamOverview }>(`/api/v1/teams/${encodeURIComponent(id)}/overview`);
export const createTeam = (body: unknown) =>
  api.post<{ team: Team }>("/api/v1/teams", body);
export const updateTeam = (id: string, body: unknown) =>
  api.patch<{ team: Team }>(`/api/v1/teams/${encodeURIComponent(id)}`, body);
export const addTeamMember = (
  teamId: string,
  body: {
    agentId: string;
    role: string;
    instructions?: string;
    runtimeBindingPolicy?: RuntimeBindingPolicy | null;
  },
) =>
  api.post<{ member: TeamMember }>(
    `/api/v1/teams/${encodeURIComponent(teamId)}/members`,
    body,
  );
export const updateTeamMember = (
  teamId: string,
  memberId: string,
  body: {
    role: string;
    instructions?: string;
    capabilityRequirements?: Record<string, unknown>;
    runtimeBindingPolicy?: RuntimeBindingPolicy | null;
    expectedTeamVersion: number;
  },
) => api.patch<{ member: TeamMember }>(
  `/api/v1/teams/${encodeURIComponent(teamId)}/members/${encodeURIComponent(memberId)}`,
  body,
);
export const removeTeamMember = (teamId: string, memberId: string) =>
  api.delete<void>(
    `/api/v1/teams/${encodeURIComponent(teamId)}/members/${encodeURIComponent(memberId)}`,
  );
export interface InboxSummary {
  unread: number;
  actionRequired: number;
  pendingApprovals: number;
  attentionTotal: number;
  byType: Record<string, number>;
}
export interface InboxOptions {
  view?: string;
  type?: string;
  archived?: string;
  cursor?: string;
  limit?: number;
}
export interface InboxPageResult {
  items: InboxItem[];
  hasMore: boolean;
  nextCursor: string;
}
export const listInbox = (tenant: string, namespace: string, options: InboxOptions = {}) =>
  api.get<InboxPageResult>(`/api/v1/inbox${query({ tenant, namespace, ...options })}`);
export const getInbox = (id: string, tenant: string, namespace: string) =>
  api.get<{ item: InboxItem }>(`/api/v1/inbox/${encodeURIComponent(id)}${query({ tenant, namespace })}`);
export const getInboxSummary = (tenant: string, namespace: string) =>
  api.get<{ summary: InboxSummary }>(`/api/v1/inbox/summary${query({ tenant, namespace })}`);
export const readInbox = (id: string, tenant?: string, namespace?: string) =>
  api.post<{ item: InboxItem }>(`/api/v1/inbox/${encodeURIComponent(id)}/read${query({ tenant, namespace })}`, {});
export const archiveInboxMessage = (id: string, tenant: string, namespace: string) =>
  api.post<{ item: InboxItem }>(`/api/v1/inbox/${encodeURIComponent(id)}/archive${query({ tenant, namespace })}`, {});
export const getApproval = (id: string) =>
  api.get<{ approval: Approval }>(`/api/v1/approvals/${encodeURIComponent(id)}`);
export const listApprovals = (
  tenant: string,
  namespace: string,
  status = "pending",
) =>
  api.get<{ items: Approval[] }>(
    `/api/v1/approvals${query({ tenant, namespace, status })}`,
  );
export const decideApproval = (
  id: string,
  status: string,
  expectedVersion: number,
  decision: unknown,
) =>
  api.post<{ approval: Approval }>(
    `/api/v1/approvals/${encodeURIComponent(id)}/decide`,
    { status, expectedVersion, decision },
  );
