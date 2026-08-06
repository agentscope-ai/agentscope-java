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

import { api, ApiError } from '@/lib/apiClient';

export interface RuntimeSession {
  id: string;
  sessionId: string;
  agentName: string;
  namespace: string;
  framework?: string;
  phase: string;
  busy?: boolean | null;
  instanceRef?: string;
  startedAt?: string;
  lastActiveAt?: string;
  instanceHealthy?: boolean;
  /** Resolved data-plane HTTP base URL for instanceRef (when registered). */
  instanceBaseUrl?: string;
  capabilities?: string[];
  contractLevel?: number;
  model?: string;
  snapshot?: {
    messageCount?: number;
    promptTokens?: number;
    completionTokens?: number;
    totalTokens?: number;
    contextPressure?: number;
    isCompacted?: boolean;
    effectiveMessageCount?: number;
    contextHash?: string;
  };
}

export interface AgentUsage {
  agentName: string;
  namespace: string;
  totalTokens: number;
  activeSessions: number;
  avgPressure?: number;
  errorCount?: number;
}

export interface SessionUsage {
  sessionFk: string;
  sessionId: string;
  agentName: string;
  namespace: string;
  phase?: string;
  totalTokens: number;
}

export interface SessionDurationRank {
  sessionFk: string;
  sessionId: string;
  agentName: string;
  namespace: string;
  phase?: string;
  durationMs: number;
  startedAt?: string;
  endedAt?: string;
  turnIndex?: number;
}

export interface SessionTurn {
  id: string;
  sessionFk: string;
  turnIndex: number;
  status: string;
  startedAt: string;
  endedAt?: string;
  durationMs?: number;
  userPreview?: string;
  promptTokens?: number;
  completionTokens?: number;
  createdAt?: string;
}

export interface HighPressureSession {
  id?: string;
  sessionId: string;
  agentName: string;
  namespace: string;
  phase?: string;
  contextPressure?: number;
  totalTokens?: number;
}

export interface StaleDataplane {
  instanceId: string;
  agentName: string;
  namespace: string;
  lastSeenAt?: string;
}

export interface OrphanSession {
  id?: string;
  sessionId: string;
  agentName: string;
  namespace: string;
  instanceRef?: string;
}

export interface FleetOverview {
  agentCount: number;
  offlineAgentCount?: number;
  historicalAgentCount?: number;
  instanceCount: number;
  healthyInstanceCount?: number;
  staleInstanceCount?: number;
  dataplaneCount: number;
  sessionCount: number;
  activeSessionCount: number;
  sessionsByPhase?: Record<string, number>;
  tokenUsage24h: number;
  errorCount24h?: number;
  topAgents?: AgentUsage[];
  topSessionsByTokens?: SessionUsage[];
  topSessionsByDuration?: SessionDurationRank[];
  topAgentsByActive?: AgentUsage[];
  staleDataplanes?: StaleDataplane[];
  orphanSessions?: OrphanSession[];
}

export interface ManagedAgentSummary {
  name: string;
  namespace: string;
  type?: string;
  runtime?: string;
  displayName?: string;
  replicas?: string;
  activeSessions?: number;
  revision?: number;
  presence?: 'live' | 'offline' | 'historical';
  healthyCount?: number;
  instanceCount?: number;
}

export interface DataPlaneEntry {
  agentName: string;
  namespace: string;
  instanceId: string;
  baseUrl: string;
  runtime?: string;
  framework?: string;
  contractLevel: number;
  capabilities?: string[];
  healthy: boolean;
  lastSeenAt: string;
  source: string;
}

export interface TokenBucket {
  bucketStart: string;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  sampleCount: number;
}

export interface OverviewTimeseries {
  metric: string;
  bucket: string;
  points: TokenBucket[];
}

export interface AgentMetric {
  id: number;
  agentName: string;
  namespace: string;
  recordedAt: string;
  activeSessions: number;
  totalMessages?: number;
  totalTokens?: number;
  avgContextPressure?: number;
  errorCount?: number;
  uptimeSeconds?: number;
}

export interface SessionCommand {
  id: string;
  sessionId: string;
  agentName: string;
  namespace: string;
  command: string;
  operator?: string;
  source?: string;
  status: string;
  code?: string;
  error?: string;
  requestedAt: string;
  completedAt?: string;
  durationMs?: number;
}

export interface SessionTask {
  id?: string;
  taskId?: string;
  subject?: string;
  name?: string;
  state?: string;
  status?: string;
  description?: string;
  [key: string]: unknown;
}

export interface InventorySubagent {
  name: string;
  description?: string;
  tools?: string[];
  workspaceMode?: string;
  url?: string;
  invokeCount?: number;
  lastInvokedAt?: string;
}

export interface InventoryWorkspace {
  path: string;
  mode?: string;
  sizeBytes?: number;
  ownerRef?: string;
}

export function fetchOverview() {
  return api.get<FleetOverview>('/api/v1/overview');
}

export function fetchOverviewTimeseries(params?: { metric?: string; bucket?: string }) {
  const q = new URLSearchParams();
  q.set('metric', params?.metric || 'tokens');
  q.set('bucket', params?.bucket || '1h');
  return api.get<OverviewTimeseries>(`/api/v1/overview/timeseries?${q}`);
}

export function fetchAgentMetrics(params?: { agent?: string; namespace?: string; since?: string }) {
  const q = new URLSearchParams();
  if (params?.agent) q.set('agent', params.agent);
  if (params?.namespace) q.set('namespace', params.namespace);
  if (params?.since) q.set('since', params.since);
  const qs = q.toString();
  return api.get<{ metrics: AgentMetric[] }>(`/api/v1/metrics/agents${qs ? `?${qs}` : ''}`);
}

export function fetchRuntimeSessions(params?: {
  agent?: string;
  phase?: string;
  namespace?: string;
  limit?: number;
  offset?: number;
}) {
  const q = new URLSearchParams();
  if (params?.agent) q.set('agent', params.agent);
  if (params?.phase) q.set('phase', params.phase);
  if (params?.namespace) q.set('namespace', params.namespace);
  if (params?.limit != null) q.set('limit', String(params.limit));
  if (params?.offset != null) q.set('offset', String(params.offset));
  const qs = q.toString();
  return api.get<{ sessions: RuntimeSession[] }>(`/api/v1/sessions${qs ? `?${qs}` : ''}`);
}

/** Prefer control-plane store UUID; fall back to framework sessionId + agent. */
export function sessionDetailPath(s: {
  id?: string;
  sessionId: string;
  agentName?: string;
  namespace?: string;
}): string {
  if (s.id) {
    return `/operate/sessions/${encodeURIComponent(s.id)}`;
  }
  const q = new URLSearchParams();
  if (s.agentName) q.set('agent', s.agentName);
  if (s.namespace) q.set('namespace', s.namespace);
  const qs = q.toString();
  return `/operate/sessions/${encodeURIComponent(s.sessionId)}${qs ? `?${qs}` : ''}`;
}

export function fetchRuntimeSession(
  id: string,
  opts?: { agent?: string; namespace?: string },
) {
  const q = new URLSearchParams();
  if (opts?.agent) q.set('agent', opts.agent);
  if (opts?.namespace) q.set('namespace', opts.namespace);
  const qs = q.toString();
  return api.get<RuntimeSession>(
    `/api/v1/sessions/${encodeURIComponent(id)}${qs ? `?${qs}` : ''}`,
  );
}

export type SessionMessageItem = {
  seq: number;
  role: string;
  content?: string;
  toolName?: string;
  toolCallId?: string;
  toolInput?: unknown;
  toolOutput?: string;
  truncated?: boolean;
  originalSize?: number;
  occurredAt?: string;
};

export type SessionMessagePage = {
  sessionId: string;
  offset: number;
  limit: number;
  total: number;
  messages: SessionMessageItem[];
  /** "transcript" | "dataplane" when provided by control plane */
  source?: string;
};

export type SessionEventItem = {
  id?: number;
  seq?: number;
  eventType?: string;
  role?: string;
  content?: string;
  toolName?: string;
  toolInput?: unknown;
  toolOutput?: string;
  tokensIn?: number;
  tokensOut?: number;
  durationMs?: number;
  occurredAt?: string;
  frameworkMeta?: unknown;
};

export function fetchSessionEvents(
  id: string,
  opts?: { limit?: number; before?: string | number; eventType?: string },
) {
  const q = new URLSearchParams();
  if (opts?.limit != null) q.set('limit', String(opts.limit));
  if (opts?.before != null && opts.before !== '') q.set('before', String(opts.before));
  if (opts?.eventType) q.set('eventType', opts.eventType);
  const qs = q.toString();
  return api.get<{ events: SessionEventItem[] }>(
    `/api/v1/sessions/${encodeURIComponent(id)}/events${qs ? `?${qs}` : ''}`,
  );
}

export function fetchSessionContext(id: string) {
  return api.get<Record<string, unknown>>(`/api/v1/sessions/${encodeURIComponent(id)}/context`);
}

export function fetchSessionMessages(
  id: string,
  opts?: {
    offset?: number;
    limit?: number;
    fromEnd?: boolean;
    agent?: string;
    namespace?: string;
  },
) {
  const q = new URLSearchParams();
  if (opts?.offset != null) q.set('offset', String(opts.offset));
  if (opts?.limit != null) q.set('limit', String(opts.limit));
  if (opts?.fromEnd) q.set('fromEnd', 'true');
  if (opts?.agent) q.set('agent', opts.agent);
  if (opts?.namespace) q.set('namespace', opts.namespace);
  const qs = q.toString();
  return api.get<SessionMessagePage>(
    `/api/v1/sessions/${encodeURIComponent(id)}/messages${qs ? `?${qs}` : ''}`,
  );
}

export function fetchSessionTasks(id: string) {
  return api.get<{ tasks?: SessionTask[] } | SessionTask[]>(`/api/v1/sessions/${encodeURIComponent(id)}/tasks`);
}

export function fetchSessionSubagentTasks(id: string) {
  return api.get<{ tasks?: SessionTask[] }>(`/api/v1/sessions/${encodeURIComponent(id)}/subagent-tasks`);
}

export function setSessionPlanMode(id: string, active: boolean) {
  return api.post<{ accepted?: boolean; active?: boolean }>(
    `/api/v1/sessions/${encodeURIComponent(id)}/plan-mode`,
    { active },
  );
}

export function fetchSessionCommands(id: string) {
  return api.get<{ commands: SessionCommand[] }>(`/api/v1/sessions/${encodeURIComponent(id)}/commands`);
}

export function fetchSessionTurns(id: string) {
  return api.get<{ turns: SessionTurn[] }>(`/api/v1/sessions/${encodeURIComponent(id)}/turns`);
}

export function compressSession(id: string, opts?: { force?: boolean; queue?: boolean }) {
  return api.post<{
    accepted?: boolean;
    commandId?: string;
    phase?: string;
    forced?: boolean;
    queued?: boolean;
    cached?: boolean;
  }>(`/api/v1/sessions/${encodeURIComponent(id)}/compress`, {
    force: opts?.force === true,
    queue: opts?.queue,
  });
}

export function terminateSession(id: string) {
  return api.post(`/api/v1/sessions/${encodeURIComponent(id)}/terminate`);
}

export function archiveSession(id: string) {
  return api.post<{ accepted?: boolean; phase?: string }>(
    `/api/v1/sessions/${encodeURIComponent(id)}/archive`,
  );
}

export function restoreSession(id: string) {
  return api.post<{ accepted?: boolean; phase?: string }>(
    `/api/v1/sessions/${encodeURIComponent(id)}/restore`,
  );
}

export function abortSession(id: string) {
  return api.post(`/api/v1/sessions/${encodeURIComponent(id)}/abort`);
}

/** Phase badge tone for Operate UI. */
export function phaseTone(phase?: string): 'default' | 'success' | 'warning' | 'danger' | 'info' {
  switch ((phase || '').toLowerCase()) {
    case 'active':
      return 'warning';
    case 'idle':
      return 'success';
    case 'compressing':
      return 'info';
    case 'terminated':
      return 'danger';
    case 'archived':
      return 'default';
    default:
      return 'default';
  }
}

/** Human hint beside phase: active = mid-turn, idle = ops allowed. */
export function phaseHint(phase?: string): string {
  switch ((phase || '').toLowerCase()) {
    case 'active':
      return 'inferencing';
    case 'idle':
      return 'ready (compress OK)';
    case 'compressing':
      return 'compress in flight';
    case 'archived':
      return 'history';
    case 'terminated':
      return 'destroyed';
    default:
      return '';
  }
}

export type AgentPresence = 'live' | 'offline' | 'historical' | 'all';

export function fetchManagedAgents(opts?: { presence?: AgentPresence }) {
  const presence = opts?.presence ?? 'live';
  const qs = new URLSearchParams({ presence });
  return api.get<{ items: ManagedAgentSummary[] }>(`/api/v1/agents?${qs}`);
}

export function fetchManagedAgent(name: string, namespace = 'default') {
  return api.get<Record<string, unknown>>(
    `/api/v1/agents/${encodeURIComponent(name)}?namespace=${encodeURIComponent(namespace)}`,
  );
}

export function fetchDataPlanes(agent?: string, namespace = 'default') {
  const q = agent ? `?agent=${encodeURIComponent(agent)}&namespace=${encodeURIComponent(namespace)}` : '';
  return api.get<{ dataplanes: DataPlaneEntry[] }>(`/api/v1/dataplanes${q}`);
}

/** Graceful GET that returns null on 404/501 (BYO inventory may be unavailable). */
export async function fetchOptional<T>(path: string): Promise<T | null> {
  try {
    return await api.get<T>(path);
  } catch (e) {
    if (e instanceof ApiError && (e.status === 404 || e.status === 501 || e.status === 503)) {
      return null;
    }
    throw e;
  }
}

export function fetchAgentSubagents(name: string, namespace = 'default') {
  return fetchOptional<{
    agent: string;
    namespace: string;
    source?: string;
    instances: Array<{
      instanceId: string;
      source?: string;
      healthy?: boolean;
      subagents: InventorySubagent[];
    }>;
  }>(`/api/v1/agents/${encodeURIComponent(name)}/subagents?namespace=${encodeURIComponent(namespace)}`);
}

export function fetchAgentWorkspaces(name: string, namespace = 'default') {
  return fetchOptional<{
    agent: string;
    namespace: string;
    source?: string;
    instances: Array<{
      instanceId: string;
      source?: string;
      healthy?: boolean;
      workspaces: InventoryWorkspace[];
    }>;
  }>(`/api/v1/agents/${encodeURIComponent(name)}/workspaces?namespace=${encodeURIComponent(namespace)}`);
}
