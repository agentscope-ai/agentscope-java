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
import { AgentToolset, McpServerSpec, SkillRef } from './agents';
import type { SubagentInfo, SubagentUpsertRequest } from './subagents';
import type { WorkspaceSkillDetail, WorkspaceSkillInfo } from './skills';

function authHeaders(): Record<string, string> {
  const token = getToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

function jsonHeaders(): Record<string, string> {
  return { ...authHeaders(), 'Content-Type': 'application/json' };
}

async function readError(res: Response, fallback: string): Promise<Error> {
  try {
    const body = await res.json();
    if (body && typeof body === 'object') {
      if ('error' in body && typeof (body as { error: unknown }).error === 'string') {
        return new Error((body as { error: string }).error);
      }
      if ('message' in body && typeof (body as { message: unknown }).message === 'string') {
        return new Error((body as { message: string }).message);
      }
    }
  } catch {
    // ignore
  }
  const text = await res.text().catch(() => '');
  return new Error(text || `${fallback} (${res.status})`);
}

export interface WorkspaceSummary {
  id: string;
  name: string;
  description?: string;
  tools?: AgentToolset[];
  mcpServers?: McpServerSpec[];
  skills?: SkillRef[];
  version: number;
  ownerId?: string;
  createdAt: number;
  updatedAt: number;
  agentsMdExists?: boolean;
  skillCount?: number;
  subagentCount?: number;
}

export async function listWorkspaces(): Promise<WorkspaceSummary[]> {
  const res = await fetch('/api/workspaces', { headers: authHeaders() });
  if (!res.ok) throw await readError(res, 'Failed to list workspaces');
  return res.json();
}

export async function createWorkspace(body: {
  name: string;
  description?: string;
}): Promise<WorkspaceSummary> {
  const res = await fetch('/api/workspaces', {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify(body),
  });
  if (!res.ok) throw await readError(res, 'Failed to create workspace');
  return res.json();
}

export async function getWorkspace(id: string): Promise<WorkspaceSummary> {
  const res = await fetch(`/api/workspaces/${encodeURIComponent(id)}`, { headers: authHeaders() });
  if (!res.ok) throw await readError(res, 'Workspace not found');
  return res.json();
}

export async function patchWorkspace(
  id: string,
  body: Partial<{
    name: string;
    description: string;
    tools: AgentToolset[];
    mcpServers: McpServerSpec[];
    skills: SkillRef[];
  }>,
): Promise<WorkspaceSummary> {
  const res = await fetch(`/api/workspaces/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    headers: jsonHeaders(),
    body: JSON.stringify(body),
  });
  if (!res.ok) throw await readError(res, 'Failed to update workspace');
  return res.json();
}

export async function deleteWorkspace(id: string): Promise<void> {
  const res = await fetch(`/api/workspaces/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok && res.status !== 204) throw await readError(res, 'Failed to delete workspace');
}

export async function readWorkspaceFile(
  id: string,
  path: string,
): Promise<{ path: string; content: string }> {
  const res = await fetch(
    `/api/workspaces/${encodeURIComponent(id)}/file?path=${encodeURIComponent(path)}`,
    { headers: authHeaders() },
  );
  if (!res.ok) throw await readError(res, 'File not found');
  return res.json();
}

export async function writeWorkspaceFile(id: string, path: string, content: string): Promise<void> {
  const res = await fetch(`/api/workspaces/${encodeURIComponent(id)}/file`, {
    method: 'PUT',
    headers: jsonHeaders(),
    body: JSON.stringify({ path, content }),
  });
  if (!res.ok) throw await readError(res, 'Failed to write file');
}

export async function getWorkspaceTools(
  id: string,
): Promise<{ tools: AgentToolset[]; mcpServers: McpServerSpec[] }> {
  const res = await fetch(`/api/workspaces/${encodeURIComponent(id)}/tools`, {
    headers: authHeaders(),
  });
  if (!res.ok) throw await readError(res, 'Failed to load tools');
  return res.json();
}

export async function putWorkspaceTools(
  id: string,
  tools: AgentToolset[],
  mcpServers: McpServerSpec[],
): Promise<void> {
  const res = await fetch(`/api/workspaces/${encodeURIComponent(id)}/tools`, {
    method: 'PUT',
    headers: jsonHeaders(),
    body: JSON.stringify({ tools, mcpServers }),
  });
  if (!res.ok) throw await readError(res, 'Failed to save tools');
}

export async function listWorkspaceResourceSkills(id: string): Promise<WorkspaceSkillInfo[]> {
  const res = await fetch(`/api/workspaces/${encodeURIComponent(id)}/skills`, {
    headers: authHeaders(),
  });
  if (!res.ok) throw await readError(res, 'Failed to list skills');
  return res.json();
}

export async function getWorkspaceResourceSkill(
  id: string,
  name: string,
): Promise<WorkspaceSkillDetail> {
  const res = await fetch(
    `/api/workspaces/${encodeURIComponent(id)}/skills/${encodeURIComponent(name)}`,
    { headers: authHeaders() },
  );
  if (!res.ok) throw await readError(res, 'Failed to load skill');
  return res.json();
}

export async function putWorkspaceResourceSkill(
  id: string,
  name: string,
  markdown: string,
  resources?: Record<string, string>,
): Promise<WorkspaceSkillInfo> {
  const res = await fetch(
    `/api/workspaces/${encodeURIComponent(id)}/skills/${encodeURIComponent(name)}`,
    {
      method: 'PUT',
      headers: jsonHeaders(),
      body: JSON.stringify({ markdown, resources }),
    },
  );
  if (!res.ok) throw await readError(res, 'Failed to save skill');
  return res.json();
}

export async function deleteWorkspaceResourceSkill(id: string, name: string): Promise<void> {
  const res = await fetch(
    `/api/workspaces/${encodeURIComponent(id)}/skills/${encodeURIComponent(name)}`,
    { method: 'DELETE', headers: authHeaders() },
  );
  if (!res.ok && res.status !== 204) throw await readError(res, 'Failed to delete skill');
}

export async function listWorkspaceResourceSubagents(id: string): Promise<SubagentInfo[]> {
  const res = await fetch(`/api/workspaces/${encodeURIComponent(id)}/subagents`, {
    headers: authHeaders(),
  });
  if (!res.ok) throw await readError(res, 'Failed to list subagents');
  return res.json();
}

export async function upsertWorkspaceResourceSubagent(
  id: string,
  name: string,
  req: SubagentUpsertRequest,
): Promise<SubagentInfo> {
  const res = await fetch(
    `/api/workspaces/${encodeURIComponent(id)}/subagents/${encodeURIComponent(name)}`,
    {
      method: 'PUT',
      headers: jsonHeaders(),
      body: JSON.stringify(req),
    },
  );
  if (!res.ok) throw await readError(res, 'Failed to save subagent');
  return res.json();
}

export async function deleteWorkspaceResourceSubagent(id: string, name: string): Promise<void> {
  const res = await fetch(
    `/api/workspaces/${encodeURIComponent(id)}/subagents/${encodeURIComponent(name)}`,
    { method: 'DELETE', headers: authHeaders() },
  );
  if (!res.ok && res.status !== 204) throw await readError(res, 'Failed to delete subagent');
}

export interface BuiltinToolCatalogEntry {
  id: string;
  harnessName?: string;
  description: string;
  group: string;
  available?: boolean;
}

export async function fetchBuiltinToolCatalog(): Promise<BuiltinToolCatalogEntry[]> {
  const res = await fetch('/api/toolsets/builtin', { headers: authHeaders() });
  if (!res.ok) throw await readError(res, 'Failed to load builtin catalog');
  return res.json();
}

export async function fetchMcpCatalog(): Promise<Record<string, unknown>[]> {
  const res = await fetch('/api/toolsets/mcp-catalog', { headers: authHeaders() });
  if (!res.ok) throw await readError(res, 'Failed to load MCP catalog');
  return res.json();
}
