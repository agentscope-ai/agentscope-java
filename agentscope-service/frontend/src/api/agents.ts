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

import { getToken } from './auth';
import { readApiError } from './http';

export type ShareTier = 'CLONE' | 'RUN' | 'EDIT';
export type GranteeType = 'USER' | 'WORKSPACE';

export interface AgentShareGrant {
  granteeType: GranteeType;
  granteeId: string;
  tier: ShareTier;
  createdAt: number;
  createdBy: string;
}

/** Matches backend AgentSpecTypes.PermissionPolicy */
export interface PermissionPolicy {
  type: 'always_allow' | 'always_ask' | 'deny' | string;
}

export interface ToolDefaultConfig {
  enabled?: boolean;
  permissionPolicy?: PermissionPolicy;
}

export interface ToolConfigEntry {
  name: string;
  enabled?: boolean;
  permissionPolicy?: PermissionPolicy;
}

/** Matches backend AgentSpecTypes.AgentToolset */
export interface AgentToolset {
  type: 'agent_toolset' | 'mcp_toolset' | string;
  defaultConfig?: ToolDefaultConfig;
  configs?: ToolConfigEntry[];
  mcpServerName?: string;
}

/** Matches backend AgentSpecTypes.McpServerSpec */
export interface McpServerSpec {
  name: string;
  type?: string;
  url?: string;
  transport?: string;
  command?: string;
  args?: string[];
  env?: Record<string, string>;
  headers?: Record<string, string>;
  queryParams?: Record<string, string>;
  enableTools?: string[];
  timeout?: string;
}

export interface SkillRef {
  type?: string;
  name?: string;
  id?: string;
  version?: string;
}

export interface AgentDefinition {
  id: string;
  name: string;
  description?: string;
  /** System prompt (API field name is `system`, not sysPrompt). */
  system?: string;
  model?: string;
  maxIters?: number;
  tools?: AgentToolset[];
  mcpServers?: McpServerSpec[];
  skills?: SkillRef[];
  scope: 'global' | 'user';
  ownerId?: string;
  createdAt: number;
  updatedAt: number;
  shares?: AgentShareGrant[];
  runAs?: string;
  forkOf?: string;
  workspacePath?: string;
  workspaceId?: string | null;
  /** Preferred environment for new sessions when caller omits environmentId. */
  defaultEnvironmentId?: string | null;
  /** Vaults auto-mounted on new sessions when caller omits vaultIds. */
  defaultVaultIds?: string[];
  /** Memory stores auto-mounted on new sessions when caller omits memoryStoreIds. */
  defaultMemoryStoreIds?: string[];
  tierForCurrentUser?: ShareTier;
  version?: number;
  archivedAt?: number | null;
}

export interface AgentVersionEntry {
  version: number;
  snapshot?: Record<string, unknown>;
  createdAt: number;
}


export interface AgentCreateRequest {
  id?: string;
  name: string;
  description?: string;
  system?: string;
  model?: string;
  maxIters?: number;
  tools?: AgentToolset[];
  mcpServers?: McpServerSpec[];
  skills?: SkillRef[];
  workspacePath?: string;
  /** First-class Workspace to link (skills/tools/AGENTS.md source). */
  workspaceId?: string;
  defaultEnvironmentId?: string | null;
  defaultVaultIds?: string[];
  defaultMemoryStoreIds?: string[];
  /** Required on PUT for optimistic locking. */
  version?: number;
}

function authHeaders() {
  return {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${getToken()}`,
  };
}

export async function listAgents(): Promise<AgentDefinition[]> {
  const res = await fetch('/api/agents', { headers: authHeaders() });
  if (!res.ok) throw await readApiError(res, 'Failed to list agents');
  return res.json();
}

export async function getAgent(id: string): Promise<AgentDefinition> {
  const res = await fetch(`/api/agents/${encodeURIComponent(id)}`, { headers: authHeaders() });
  if (!res.ok) throw await readApiError(res, 'Failed to load agent');
  return res.json();
}

export async function createAgent(req: AgentCreateRequest): Promise<AgentDefinition> {
  const res = await fetch('/api/agents', {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) throw await readApiError(res, 'Failed to create agent');
  return res.json();
}

export async function updateAgent(
  id: string,
  req: AgentCreateRequest,
): Promise<AgentDefinition> {
  const res = await fetch(`/api/agents/${encodeURIComponent(id)}`, {
    method: 'PUT',
    headers: authHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) throw await readApiError(res, 'Failed to update agent');
  return res.json();
}

export async function deleteAgent(id: string): Promise<void> {
  const res = await fetch(`/api/agents/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok && res.status !== 204) throw await readApiError(res, 'Failed to delete agent');
}

export async function archiveAgent(id: string): Promise<AgentDefinition> {
  const res = await fetch(`/api/agents/${encodeURIComponent(id)}/archive`, {
    method: 'POST',
    headers: authHeaders(),
  });
  if (!res.ok) throw await readApiError(res, 'Failed to archive agent');
  return res.json();
}

export async function listVersions(id: string): Promise<AgentVersionEntry[]> {
  const res = await fetch(`/api/agents/${encodeURIComponent(id)}/versions`, { headers: authHeaders() });
  if (!res.ok) throw await readApiError(res, 'Failed to list versions');
  return res.json();
}

export async function getVersion(id: string, version: number): Promise<AgentVersionEntry> {
  const res = await fetch(
    `/api/agents/${encodeURIComponent(id)}/versions/${version}`,
    { headers: authHeaders() },
  );
  if (!res.ok) throw await readApiError(res, 'Failed to load version');
  return res.json();
}

