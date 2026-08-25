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
import { authHeaders, readApiError } from './http';

export interface ManagedSession {
  id: string;
  ownerId?: string;
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
}

export interface SessionEvent {
  id: string;
  sessionId: string;
  seq: number;
  type: string;
  payload?: Record<string, unknown>;
  processedAt?: number | null;
  createdAt: number;
}

export interface CreateManagedSessionRequest {
  agent: string | { type?: string; id: string; version?: number };
  /** Optional when the agent has defaultEnvironmentId. */
  environmentId?: string;
  memoryStoreIds?: string[];
  vaultIds?: string[];
  resources?: Array<{ type: string; fileId?: string; filename?: string; content?: string }>;
  agentOverrides?: Record<string, unknown>;
}

export interface UpdateManagedSessionRequest {
  agentOverrides?: Record<string, unknown>;
  environmentId?: string;
  memoryStoreIds?: string[];
  vaultIds?: string[];
}

export type ManagedSessionListStatus = 'active' | 'archived' | 'all';

export interface InboundEvent {
  type: string;
  payload?: Record<string, unknown>;
}

/** Parsed from product `externalKey` = `team|{namespace}/{teamName}|{memberName}`. */
export interface TeamSessionRef {
  namespace: string;
  teamName: string;
  memberName: string;
}

export function parseTeamExternalKey(key?: string | null): TeamSessionRef | null {
  if (!key || !key.startsWith('team|')) return null;
  const rest = key.slice('team|'.length);
  const pipe = rest.lastIndexOf('|');
  if (pipe <= 0) return null;
  const nsTeam = rest.slice(0, pipe);
  const memberName = rest.slice(pipe + 1);
  const slash = nsTeam.indexOf('/');
  if (slash <= 0 || !memberName) return null;
  return {
    namespace: nsTeam.slice(0, slash),
    teamName: nsTeam.slice(slash + 1),
    memberName,
  };
}

export function isTeamOriginatedSession(s: Pick<ManagedSession, 'externalKey'>): boolean {
  return parseTeamExternalKey(s.externalKey) != null;
}

export function teamDetailPath(ref: TeamSessionRef): string {
  return `/teams/${encodeURIComponent(ref.teamName)}?namespace=${encodeURIComponent(ref.namespace)}`;
}


export async function createManagedSession(req: CreateManagedSessionRequest): Promise<ManagedSession> {
  const res = await fetch('/api/sessions', {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) throw await readApiError(res, 'Failed to create session');
  return res.json();
}

export async function listManagedSessions(
  agentId?: string,
  status: ManagedSessionListStatus = 'active',
): Promise<ManagedSession[]> {
  const params = new URLSearchParams();
  if (agentId) params.set('agentId', agentId);
  if (status && status !== 'active') params.set('status', status);
  // Always send status=active explicitly for clarity when listing active; server defaults match.
  if (status === 'active') params.set('status', 'active');
  const qs = params.toString() ? `?${params.toString()}` : '';
  const res = await fetch(`/api/sessions${qs}`, { headers: authHeaders() });
  if (!res.ok) throw await readApiError(res, 'Failed to list sessions');
  return res.json();
}

export async function getManagedSession(id: string): Promise<ManagedSession> {
  const res = await fetch(`/api/sessions/${encodeURIComponent(id)}`, { headers: authHeaders() });
  if (!res.ok) throw await readApiError(res, 'Failed to load session');
  return res.json();
}

export async function updateManagedSession(
  id: string,
  req: UpdateManagedSessionRequest,
): Promise<ManagedSession> {
  const res = await fetch(`/api/sessions/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    headers: authHeaders(),
    body: JSON.stringify(req),
  });
  if (!res.ok) throw await readApiError(res, 'Failed to update session');
  return res.json();
}

export async function archiveManagedSession(id: string): Promise<ManagedSession> {
  const res = await fetch(`/api/sessions/${encodeURIComponent(id)}/archive`, {
    method: 'POST',
    headers: authHeaders(),
  });
  if (!res.ok) throw await readApiError(res, 'Failed to archive session');
  return res.json();
}

export async function restoreManagedSession(id: string): Promise<ManagedSession> {
  const res = await fetch(`/api/sessions/${encodeURIComponent(id)}/restore`, {
    method: 'POST',
    headers: authHeaders(),
  });
  if (!res.ok) throw await readApiError(res, 'Failed to restore session');
  return res.json();
}

export async function deleteManagedSession(id: string): Promise<void> {
  const res = await fetch(`/api/sessions/${encodeURIComponent(id)}`, {
    method: 'DELETE',
    headers: authHeaders(),
  });
  if (!res.ok && res.status !== 204) throw await readApiError(res, 'Failed to delete session');
}

export async function postEvents(sessionId: string, events: InboundEvent[]): Promise<SessionEvent[]> {
  const res = await fetch(`/api/sessions/${encodeURIComponent(sessionId)}/events`, {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify({ events }),
  });
  if (!res.ok) throw await readApiError(res, 'Failed to post events');
  return res.json();
}

export async function listEvents(
  sessionId: string,
  options?: { after?: number; types?: string[] },
): Promise<SessionEvent[]> {
  const params = new URLSearchParams();
  if (options?.after != null) params.set('after', String(options.after));
  for (const t of options?.types ?? []) {
    params.append('types', t);
  }
  const qs = params.toString();
  const res = await fetch(
    `/api/sessions/${encodeURIComponent(sessionId)}/events${qs ? `?${qs}` : ''}`,
    { headers: authHeaders() },
  );
  if (!res.ok) throw await readApiError(res, 'Failed to list events');
  return res.json();
}

export interface EventStreamHandle {
  close: () => void;
}

const sleep = (ms: number) => new Promise(resolve => setTimeout(resolve, ms));

/**
 * Subscribes to session events over SSE. Uses fetch (not native EventSource) so JWT Bearer auth
 * works. Reconnects automatically when the stream ends or fails, resuming from the last seen
 * sequence via {@code getAfter} so no events are lost across plane restarts or network drops.
 */
export function streamEvents(
  sessionId: string,
  onEvent: (event: SessionEvent) => void,
  onError?: (err: Error) => void,
  options?: {
    eventDeltas?: string[];
    after?: number;
    /** Called before each (re)connect to compute the resume cursor. */
    getAfter?: () => number | undefined;
    /** Base reconnect delay; doubles up to {@link maxRetryMs} and resets on progress. */
    retryMs?: number;
    maxRetryMs?: number;
  },
): EventStreamHandle {
  const token = getToken();
  const controller = new AbortController();
  let closed = false;
  let backoffMs = Math.max(500, options?.retryMs ?? 2000);
  const maxRetryMs = options?.maxRetryMs ?? 30000;

  async function connect(): Promise<void> {
    const after = options?.getAfter ? options.getAfter() : options?.after;
    const params = new URLSearchParams();
    if (after != null && after > 0) {
      params.set('after', String(after));
    }
    for (const t of options?.eventDeltas ?? []) {
      params.append('event_deltas', t);
    }
    const qs = params.toString();
    const res = await fetch(
      `/api/sessions/${encodeURIComponent(sessionId)}/events/stream${qs ? `?${qs}` : ''}`,
      {
        headers: token ? { Authorization: `Bearer ${token}` } : {},
        signal: controller.signal,
      },
    );
    if (!res.ok || !res.body) {
      throw new Error(`Event stream failed: ${res.status}`);
    }
    const reader = res.body.getReader();
    const dec = new TextDecoder();
    let buf = '';
    while (!closed) {
      const { value, done } = await reader.read();
      if (done) break;
      buf += dec.decode(value, { stream: true });
      let idx;
      while ((idx = buf.indexOf('\n\n')) >= 0) {
        const block = buf.slice(0, idx);
        buf = buf.slice(idx + 2);
        let data = '';
        for (const ln of block.split('\n')) {
          if (ln.startsWith('data:')) data += ln.slice(5).trim();
        }
        if (!data) continue;
        try {
          onEvent(JSON.parse(data) as SessionEvent);
          // Any delivered event means the connection is healthy; reset backoff.
          backoffMs = Math.max(500, options?.retryMs ?? 2000);
        } catch {
          // ignore malformed frames
        }
      }
    }
  }

  (async () => {
    while (!closed) {
      try {
        await connect();
      } catch (e: unknown) {
        if (closed) return;
        if (e instanceof Error && e.name === 'AbortError') return;
        onError?.(e instanceof Error ? e : new Error(String(e)));
      }
      if (closed) return;
      const delayMs = backoffMs;
      backoffMs = Math.min(maxRetryMs, backoffMs * 2);
      await sleep(delayMs);
    }
  })();

  return {
    close: () => {
      closed = true;
      controller.abort();
    },
  };
}

export async function postToolConfirmation(
  sessionId: string,
  toolUseId: string,
  allow: boolean,
  denyMessage?: string,
): Promise<SessionEvent[]> {
  return postEvents(sessionId, [{
    type: 'user.tool_confirmation',
    payload: {
      tool_use_id: toolUseId,
      toolUseId,
      allow,
      ...(denyMessage ? { denyMessage } : {}),
    },
  }]);
}

export async function postUserMessage(sessionId: string, text: string): Promise<SessionEvent[]> {
  return postEvents(sessionId, [{ type: 'user.message', payload: { text } }]);
}
