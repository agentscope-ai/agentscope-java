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

export interface SubagentInfo {
  name: string;
  description: string;
  model?: string;
  maxIters?: number;
  tools?: string[];
  workspaceMode: 'isolated' | 'shared';
  workspacePath?: string;
  hasInlineBody: boolean;
  sourceAgentId?: string;
}

export interface SubagentUpsertRequest {
  description: string;
  model?: string;
  maxIters?: number;
  tools?: string[];
  workspaceMode?: string;
  workspacePath?: string;
  inlineBody?: string;
  sourceAgentId?: string;
}

function authHeaders(): Record<string, string> {
  const token = getToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

function jsonHeaders(): Record<string, string> {
  return { ...authHeaders(), 'Content-Type': 'application/json' };
}

function base(agentId: string): string {
  return `/api/agents/${encodeURIComponent(agentId)}/workspace/subagents`;
}

export async function listSubagents(agentId: string): Promise<SubagentInfo[]> {
  const res = await fetch(base(agentId), { headers: authHeaders() });
  if (!res.ok) throw await readApiError(res, 'Failed to list subagents');
  return res.json();
}

export async function upsertSubagent(
  agentId: string,
  name: string,
  req: SubagentUpsertRequest,
): Promise<SubagentInfo> {
  const res = await fetch(`${base(agentId)}/${encodeURIComponent(name)}`, {
    method: 'PUT',
    headers: jsonHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) {
    throw await readApiError(res, 'Failed to save subagent');
  }
  return res.json();
}

export async function createFromAgent(
  agentId: string,
  sourceAgentId: string,
  name?: string,
): Promise<SubagentInfo> {
  const res = await fetch(`${base(agentId)}/from-agent`, {
    method: 'POST',
    headers: jsonHeaders(),
    body: JSON.stringify({ sourceAgentId, name }),
  });
  if (!res.ok) {
    throw await readApiError(res, 'Failed to create subagent from agent');
  }
  return res.json();
}

export async function deleteSubagent(agentId: string, name: string): Promise<void> {
  const res = await fetch(`${base(agentId)}/${encodeURIComponent(name)}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok && res.status !== 204) {
    throw await readApiError(res, 'Failed to delete subagent');
  }
}
