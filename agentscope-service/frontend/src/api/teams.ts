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

import { api } from '@/lib/apiClient';

export type TeamPhase = 'Pending' | 'Running' | 'Idle' | 'Completed' | 'Failed' | string;
export type MemberPhase = 'Joining' | 'Working' | 'Idle' | 'Lost' | 'Failed' | 'Shutdown' | string;
export type TaskState = 'pending' | 'in_progress' | 'completed' | 'failed' | string;

export interface Team {
  id?: string;
  name: string;
  namespace: string;
  objective: string;
  phase: TeamPhase;
  leadRef?: string;
  leadPrompt?: string;
  startedAt?: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface TeamMember {
  memberName: string;
  agentRef: string;
  prompt?: string;
  planApproval?: boolean;
  planText?: string;
  planStatus?: 'pending' | 'approved' | 'rejected' | string;
  origin?: string;
  deployMode?: 'managed' | 'byo' | string;
  managedAgentId?: string;
  ownerId?: string;
  phase: MemberPhase;
  sessionId?: string;
  managedSessionId?: string;
  instanceRef?: string;
  currentTask?: string;
  restartCount?: number;
  lastRestartReason?: string;
}

export interface TeamTask {
  taskId: string;
  teamName?: string;
  namespace?: string;
  subject: string;
  description?: string;
  state: TaskState;
  owner?: string;
  blockedBy?: string[];
  result?: string;
  version: number;
  createdAt?: string;
  updatedAt?: string;
}

export interface TeamMessage {
  id: string;
  fromMember: string;
  toMember: string;
  content: string;
  kind?: string;
  delivered?: boolean;
  createdAt?: string;
}

export interface TeamTaskSummary {
  total: number;
  pending: number;
  inProgress: number;
  completed: number;
}

export interface TeamCreateLead {
  agentRef: string;
  prompt?: string;
  deployMode?: 'managed' | 'byo';
  managedAgentId?: string;
  ownerId?: string;
}

export interface TeamCreateMember {
  name: string;
  agentRef: string;
  prompt?: string;
  planApproval?: boolean;
  deployMode?: 'managed' | 'byo';
  managedAgentId?: string;
  ownerId?: string;
}

export interface TeamCreateRequest {
  name: string;
  namespace?: string;
  objective: string;
  lead: TeamCreateLead;
  members?: TeamCreateMember[];
}

function nsQuery(namespace?: string) {
  const ns = namespace || 'default';
  return `namespace=${encodeURIComponent(ns)}`;
}

export function listTeams(namespace?: string) {
  const q = namespace ? `?${nsQuery(namespace)}` : '';
  return api.get<{ items: Team[] }>(`/api/v1/teams${q}`);
}

export function getTeam(teamName: string, namespace = 'default') {
  return api.get<{
    team: Team;
    members: TeamMember[];
    tasks: TeamTaskSummary;
  }>(`/api/v1/teams/${encodeURIComponent(teamName)}?${nsQuery(namespace)}`);
}

export function createTeam(body: TeamCreateRequest) {
  return api.post<{ team: Team; members: TeamMember[] }>('/api/v1/teams', body);
}

export function deleteTeam(teamName: string, namespace = 'default') {
  return api.delete(`/api/v1/teams/${encodeURIComponent(teamName)}?${nsQuery(namespace)}`);
}

export function completeTeam(teamName: string, namespace = 'default') {
  return api.post<{ team: Team }>(
    `/api/v1/teams/${encodeURIComponent(teamName)}/complete?${nsQuery(namespace)}`,
    {},
  );
}

export function removeTeamMember(teamName: string, memberName: string, namespace = 'default') {
  return api.delete(
    `/api/v1/teams/${encodeURIComponent(teamName)}/members/${encodeURIComponent(memberName)}?${nsQuery(namespace)}`,
  );
}

export function unclaimTeamTask(
  teamName: string,
  taskId: string,
  resourceVersion?: number,
  namespace = 'default',
) {
  return api.post<TeamTask>(
    `/api/v1/teams/${encodeURIComponent(teamName)}/tasks/${encodeURIComponent(taskId)}/unclaim?${nsQuery(namespace)}`,
    {
      resourceVersion: resourceVersion != null ? String(resourceVersion) : undefined,
    },
  );
}

export function submitMemberPlan(
  teamName: string,
  memberName: string,
  planText: string,
  namespace = 'default',
) {
  return api.post(
    `/api/v1/teams/${encodeURIComponent(teamName)}/members/${encodeURIComponent(memberName)}/plan?${nsQuery(namespace)}`,
    { planText },
  );
}

export function approveMemberPlan(teamName: string, memberName: string, namespace = 'default') {
  return api.post(
    `/api/v1/teams/${encodeURIComponent(teamName)}/members/${encodeURIComponent(memberName)}/plan/approve?${nsQuery(namespace)}`,
    {},
  );
}

export function rejectMemberPlan(teamName: string, memberName: string, namespace = 'default') {
  return api.post(
    `/api/v1/teams/${encodeURIComponent(teamName)}/members/${encodeURIComponent(memberName)}/plan/reject?${nsQuery(namespace)}`,
    {},
  );
}

export function listTeamMembers(teamName: string, namespace = 'default') {
  return api.get<{ lead?: TeamMember; members: TeamMember[] }>(
    `/api/v1/teams/${encodeURIComponent(teamName)}/members?${nsQuery(namespace)}`,
  );
}

export function spawnTeamMember(
  teamName: string,
  body: TeamCreateMember,
  namespace = 'default',
) {
  return api.post<{ member: TeamMember; status: string }>(
    `/api/v1/teams/${encodeURIComponent(teamName)}/members?${nsQuery(namespace)}`,
    body,
  );
}

export function listTeamTasks(teamName: string, namespace = 'default') {
  return api.get<{ tasks: TeamTask[]; summary: TeamTaskSummary }>(
    `/api/v1/teams/${encodeURIComponent(teamName)}/tasks?${nsQuery(namespace)}`,
  );
}

export function createTeamTask(
  teamName: string,
  body: { subject: string; description?: string; blockedBy?: string[]; owner?: string },
  namespace = 'default',
) {
  return api.post<TeamTask>(
    `/api/v1/teams/${encodeURIComponent(teamName)}/tasks?${nsQuery(namespace)}`,
    body,
  );
}

export function assignTeamTask(
  teamName: string,
  taskId: string,
  owner: string,
  resourceVersion?: number,
  namespace = 'default',
) {
  return api.post<TeamTask>(
    `/api/v1/teams/${encodeURIComponent(teamName)}/tasks/${encodeURIComponent(taskId)}/assign?${nsQuery(namespace)}`,
    {
      owner,
      resourceVersion: resourceVersion != null ? String(resourceVersion) : undefined,
    },
  );
}

export function claimTeamTask(
  teamName: string,
  taskId: string,
  claimedBy: string,
  resourceVersion?: number,
  namespace = 'default',
) {
  return api.post<TeamTask>(
    `/api/v1/teams/${encodeURIComponent(teamName)}/tasks/${encodeURIComponent(taskId)}/claim?${nsQuery(namespace)}`,
    {
      claimedBy,
      resourceVersion: resourceVersion != null ? String(resourceVersion) : undefined,
    },
  );
}

export function completeTeamTask(
  teamName: string,
  taskId: string,
  result?: string,
  namespace = 'default',
) {
  return api.post<TeamTask>(
    `/api/v1/teams/${encodeURIComponent(teamName)}/tasks/${encodeURIComponent(taskId)}/complete?${nsQuery(namespace)}`,
    { result },
  );
}

export function listTeamMessages(teamName: string, limit = 50, namespace = 'default') {
  return api.get<{ messages: TeamMessage[] }>(
    `/api/v1/teams/${encodeURIComponent(teamName)}/messages?${nsQuery(namespace)}&limit=${limit}`,
  );
}

export function sendTeamMessage(
  teamName: string,
  body: { from: string; to: string; content: string },
  namespace = 'default',
) {
  return api.post<TeamMessage>(
    `/api/v1/teams/${encodeURIComponent(teamName)}/messages?${nsQuery(namespace)}`,
    body,
  );
}

export function listTeamEvents(teamName: string, after = '', limit = 50, namespace = 'default') {
  const params = new URLSearchParams({
    namespace,
    limit: String(limit),
  });
  if (after) params.set('after', after);
  return api.get<{ events: TeamMessage[]; after?: string }>(
    `/api/v1/teams/${encodeURIComponent(teamName)}/events?${params}`,
  );
}

export function teamPhaseTone(
  phase?: string,
): 'default' | 'success' | 'warning' | 'danger' | 'info' {
  switch ((phase || '').toLowerCase()) {
    case 'running':
    case 'working':
      return 'warning';
    case 'completed':
    case 'idle':
      return 'success';
    case 'pending':
    case 'joining':
      return 'info';
    case 'failed':
    case 'lost':
      return 'danger';
    default:
      return 'default';
  }
}

export function chatSessionPath(member: TeamMember): string | null {
  const id = managedChatSessionId(member);
  if (!id) return null;
  return `/sessions/${encodeURIComponent(id)}`;
}

/** Managed member session id for in-page chat (null for BYO / unbound). */
export function managedChatSessionId(member: TeamMember): string | null {
  if (member.deployMode !== 'managed') return null;
  const id = member.managedSessionId || member.sessionId;
  if (!id) return null;
  return id;
}
