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

/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

import { api } from '@/lib/apiClient';
export type DataPlaneKind =
  | 'managed'
  | 'external-application'
  | 'hosted-runtime';

export interface RuntimeHost {
  id: string;
  hostKey: string;
  tenant: string;
  namespace: string;
  poolName: string;
  daemonVersion?: string;
  os?: string;
  arch?: string;
  state: string;
  capacity: number;
  active: number;
  lastSeenAt: string;
  capabilities?: unknown;
  labels?: unknown;
  leaseGeneration?: number;
  createdAt?: string;
  updatedAt?: string;
}

export interface RuntimeProfile {
  id: string;
  tenant: string;
  namespace: string;
  name: string;
  provider: string;
  runtime?: string;
  version: number;
  configuration?: unknown;
  requirements?: unknown;
  createdAt?: string;
  updatedAt?: string;
}

export interface RuntimePool {
  id: string;
  tenant: string;
  namespace: string;
  name: string;
  version: number;
  hostSelector?: unknown;
  configuration?: unknown;
  createdAt?: string;
  updatedAt?: string;
}

export interface AgentInstance {
  id: string;
  agentId: string;
  bindingId: string;
  /** Legacy deployments may still provide this denormalized display field. */
  agentName?: string;
  backendKind: DataPlaneKind;
  framework?: string;
  health: string;
  capacity: number;
  activeSessions: number;
  lastSeenAt: string;
  tenant: string;
  namespace: string;
  instanceKey?: string;
  frameworkVersion?: string;
  sdkVersion?: string;
  capabilities?: unknown;
  labels?: unknown;
  generation?: number;
}

export interface ExecutionAttempt {
  id: string;
  taskId: string;
  tenant: string;
  namespace: string;
  attempt: number;
  backendKind: DataPlaneKind;
  runtimeProfileName?: string;
  runtimePoolName?: string;
  requiredCapabilities?: unknown;
  hostId?: string;
  sessionId?: string;
  providerSessionId?: string;
  workspaceKey?: string;
  state: string;
  leaseOwner?: string;
  fencingToken: number;
  leaseExpiresAt?: string;
  checkpoint?: unknown;
  result?: unknown;
  failureCode?: string;
  failureMessage?: string;
  version: number;
  createdAt: string;
  updatedAt: string;
  startedAt?: string;
  completedAt?: string;
}

function scopeQuery(tenant?: string, namespace?: string, extra?: Record<string, string>) {
  const params = new URLSearchParams(extra);
  if (tenant) params.set('tenant', tenant);
  if (namespace) params.set('namespace', namespace);
  return params.size ? `?${params}` : '';
}

export function listRuntimeHosts(tenant?: string, namespace?: string, poolName?: string, state?: string) {
  const extra: Record<string, string> = {};
  if (poolName) extra.poolName = poolName;
  if (state) extra.state = state;
  return api.get<{ items: RuntimeHost[] }>(`/api/v1/runtime-hosts${scopeQuery(tenant, namespace, extra)}`);
}

export function getRuntimeHost(id: string) {
  return api.get<{ host: RuntimeHost }>(`/api/v1/runtime-hosts/${encodeURIComponent(id)}`);
}

export function updateRuntimeHostCapacity(id: string, capacity: number, expectedCapacity: number) {
  return api.patch<{ host: RuntimeHost }>(`/api/v1/runtime-hosts/${encodeURIComponent(id)}/capacity`, { capacity, expectedCapacity });
}

export function setRuntimeHostDraining(id: string, draining: boolean) {
  return api.post<{ host: RuntimeHost }>(
    `/api/v1/runtime-hosts/${encodeURIComponent(id)}/${draining ? 'drain' : 'resume'}`,
    {},
  );
}

export function listRuntimeProfiles(tenant?: string, namespace?: string) {
  return api.get<{ items: RuntimeProfile[] }>(`/api/v1/runtime-profiles${scopeQuery(tenant, namespace)}`);
}

export function upsertRuntimeProfile(body: {
  tenant: string;
  namespace: string;
  name: string;
  provider: string;
  runtime?: string;
  configuration?: unknown;
  requirements?: unknown;
}) {
  return api.post<{ profile: RuntimeProfile }>('/api/v1/runtime-profiles', body);
}

export function listRuntimePools(tenant?: string, namespace?: string) {
  return api.get<{ items: RuntimePool[] }>(`/api/v1/runtime-pools${scopeQuery(tenant, namespace)}`);
}

export function upsertRuntimePool(body: {
  tenant: string;
  namespace: string;
  name: string;
  hostSelector?: unknown;
  configuration?: unknown;
}) {
  return api.post<{ pool: RuntimePool }>('/api/v1/runtime-pools', body);
}

export function listAgentInstances(tenant?: string, namespace?: string, agentName?: string) {
  const extra = agentName ? { agentName } : undefined;
  return api.get<{ items: AgentInstance[] }>(`/api/v1/agent-instances${scopeQuery(tenant, namespace, extra)}`);
}
