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

import { authHeaders, readApiError } from './http';

export interface ManagedSession {
  id: string;
  ownerId?: string;
  runtimeKind?: string;
  agentId: string;
  agentOwnerId?: string;
  agentVersion?: number | null;
  agentRefType?: string;
  agentOverridesJson?: string | null;
  environmentId: string;
  memoryStoreIds?: string[];
  vaultIds?: string[];
  status: string;
  stopReason?: Record<string, unknown> | null;
  createdAt: number;
  updatedAt: number;
  archivedAt?: number | null;
  externalKey?: string | null;
  title?: string | null;
  metadata?: Record<string, string>;
  runtimeStatus?: string;
  configurationRevision?: string;
  lifecycleCommandId?: string;
  cleanupStatus?: string;
}

export interface CreateManagedSessionRequest {
  agent: string | { type?: string; id: string; version?: number };
  /** Optional when the agent has defaultEnvironmentId. */
  environmentId?: string;
  title?: string | null;
  metadata?: Record<string, string>;
}

export interface UpdateManagedSessionRequest {
  title?: string | null;
  metadata?: Record<string, string>;
}

export type ManagedSessionListStatus = 'active' | 'archived' | 'all';

/** Parsed from product `externalKey` = `agent-task|{agentTaskId}`. */
export interface AgentTaskSessionRef {
  agentTaskId: string;
}

export function parseAgentTaskExternalKey(key?: string | null): AgentTaskSessionRef | null {
  if (!key || !key.startsWith('agent-task|')) return null;
  const [agentTaskId, ...extra] = key.slice('agent-task|'.length).split('|');
  if (!agentTaskId || extra.length > 0) return null;
  return { agentTaskId };
}

export function isAgentTaskSession(s: Pick<ManagedSession, 'externalKey'>): boolean {
  return parseAgentTaskExternalKey(s.externalKey) != null;
}

export function agentTaskDetailPath(ref: AgentTaskSessionRef): string {
  return `/control/tasks/${encodeURIComponent(ref.agentTaskId)}`;
}


export interface ManagedSessionWire {
  id: string;
  agent: { id: string; version?: number; name?: string };
  environment_id: string;
  status: string;
  created_at: string;
  updated_at: string;
  title?: string | null;
  metadata?: Record<string, string>;
  managed_agents: {
    runtime_kind: string;
    lifecycle: string;
    owner_id?: string;
    agent_owner_id?: string;
    memory_store_ids?: string[];
    vault_ids?: string[];
    archived_at?: string | null;
    external_key?: string | null;
    configuration_revision?: string;
    lifecycle_command_id?: string;
    cleanup_status?: string;
  };
}

export interface ManagedLifecycleReceipt {
  id: string;
  session_id: string;
  lifecycle: 'active' | 'archived' | 'deleted';
  status: 'stopping' | 'cleaning' | 'completed';
  revision: number;
  failure_code?: string;
}

export interface ManagedDeletionReceipt {
  id: string;
  type: 'session_deleted';
  lifecycle_command_id: string;
  cleanup_status: ManagedLifecycleReceipt['status'];
}

export function managedLifecyclePending(session: Pick<ManagedSession, 'cleanupStatus'>): boolean {
  return session.cleanupStatus === 'stopping' || session.cleanupStatus === 'cleaning';
}

const base = '/v1/sessions';
const path = (id: string) => `${base}/${encodeURIComponent(id)}`;
async function request<T>(url: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch(url, { ...init, headers: { ...authHeaders(), 'Content-Type': 'application/json', 'Managed-Agents-Extensions': 'turn-inputs-v1', ...init.headers } });
  if (!response.ok) throw await readApiError(response, 'Session request failed');
  return response.status === 204 ? undefined as T : response.json() as Promise<T>;
}

export function managedSessionCreateBody(req: CreateManagedSessionRequest): Record<string, unknown> {
  if (Object.entries(req).some(([key, value]) => value !== undefined && !['agent', 'environmentId', 'title', 'metadata'].includes(key)))
    throw new Error('unsupported_capability: Only agent, environment, title and metadata are supported');
  return { agent: typeof req.agent === 'string' ? req.agent : { type: 'agent', id: req.agent.id, version: req.agent.version },
    environment_id: req.environmentId, title: req.title, metadata: req.metadata };
}

export function normalizeManagedSession(value: ManagedSessionWire): ManagedSession {
  if (!value.agent?.id || value.managed_agents?.runtime_kind !== 'managed' || !value.managed_agents.lifecycle)
    throw new Error('Unsupported Managed Session resource schema');
  const platform = value.managed_agents;
  return { id: value.id, ownerId: platform.owner_id, runtimeKind: platform.runtime_kind,
    agentId: value.agent.id, agentOwnerId: platform.agent_owner_id, agentVersion: value.agent.version,
    agentRefType: 'version', environmentId: value.environment_id,
    memoryStoreIds: platform.memory_store_ids, vaultIds: platform.vault_ids,
    status: platform.lifecycle, runtimeStatus: value.status, title: value.title, metadata: value.metadata,
    configurationRevision: platform.configuration_revision,
    lifecycleCommandId: platform.lifecycle_command_id, cleanupStatus: platform.cleanup_status,
    createdAt: Date.parse(value.created_at), updatedAt: Date.parse(value.updated_at),
    archivedAt: platform.archived_at ? Date.parse(platform.archived_at) : null, externalKey: platform.external_key };
}

export async function createManagedSession(req: CreateManagedSessionRequest, idempotencyKey = crypto.randomUUID()): Promise<ManagedSession> {
  return normalizeManagedSession(await request<ManagedSessionWire>(base, { method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey }, body: JSON.stringify(managedSessionCreateBody(req)) }));
}

export async function listManagedSessions(agentId?: string, lifecycle: ManagedSessionListStatus = 'active'): Promise<ManagedSession[]> {
  const sessions: ManagedSession[] = [];
  let after: string | null = null;
  do {
    const query: URLSearchParams = new URLSearchParams({ lifecycle, limit: '100', ...(agentId ? { agent_id: agentId } : {}), ...(after ? { after } : {}) });
    const page: { data: ManagedSessionWire[]; next_cursor: string | null } = await request<{ data: ManagedSessionWire[]; next_cursor: string | null }>(`${base}?${query}`);
    sessions.push(...page.data.map(normalizeManagedSession));
    after = page.next_cursor;
  } while (after);
  return sessions;
}

export async function getManagedSession(id: string): Promise<ManagedSession> {
  return normalizeManagedSession(await request<ManagedSessionWire>(path(id)));
}
export async function updateManagedSession(id: string, req: UpdateManagedSessionRequest): Promise<ManagedSession> {
  return normalizeManagedSession(await request<ManagedSessionWire>(path(id), { method: 'POST', body: JSON.stringify(req) }));
}
export async function archiveManagedSession(id: string): Promise<ManagedSession> {
  return normalizeManagedSession(await request<ManagedSessionWire>(`${path(id)}/archive`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() } }));
}
export async function restoreManagedSession(id: string): Promise<ManagedSession> {
  return normalizeManagedSession(await request<ManagedSessionWire>(`${path(id)}/restore`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() } }));
}
export function getManagedLifecycleReceipt(id: string, commandId: string): Promise<ManagedLifecycleReceipt> {
  return request(`${path(id)}/lifecycle/${encodeURIComponent(commandId)}`);
}
export async function deleteManagedSession(id: string): Promise<ManagedDeletionReceipt> {
  return request(`${path(id)}`, { method: 'DELETE', headers: { 'Idempotency-Key': crypto.randomUUID() } });
}
