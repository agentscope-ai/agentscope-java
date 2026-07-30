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
  environmentId: string;
  memoryStoreIds?: string[];
  vaultIds?: string[];
  resources?: Array<{ type: string; fileId?: string; filename?: string; content?: string }>;
  agentOverrides?: Record<string, unknown>;
}

export interface UpdateManagedSessionRequest {
  agentOverrides: Record<string, unknown>;
}

export interface InboundEvent {
  type: string;
  payload?: Record<string, unknown>;
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

export async function listManagedSessions(agentId?: string): Promise<ManagedSession[]> {
  const qs = agentId ? `?agentId=${encodeURIComponent(agentId)}` : '';
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

export async function listEvents(sessionId: string, after?: number): Promise<SessionEvent[]> {
  const qs = after != null ? `?after=${after}` : '';
  const res = await fetch(
    `/api/sessions/${encodeURIComponent(sessionId)}/events${qs}`,
    { headers: authHeaders() },
  );
  if (!res.ok) throw await readApiError(res, 'Failed to list events');
  return res.json();
}

export interface EventStreamHandle {
  close: () => void;
}

/**
 * Subscribes to session events over SSE. Uses fetch (not native EventSource) so JWT Bearer auth works.
 */
export function streamEvents(
  sessionId: string,
  onEvent: (event: SessionEvent) => void,
  onError?: (err: Error) => void,
  options?: { eventDeltas?: string[] },
): EventStreamHandle {
  const token = getToken();
  const controller = new AbortController();
  let closed = false;

  (async () => {
    try {
      const params = new URLSearchParams();
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
          const lines = block.split('\n');
          let data = '';
          for (const ln of lines) {
            if (ln.startsWith('data:')) data += ln.slice(5).trim();
          }
          if (!data) continue;
          try {
            onEvent(JSON.parse(data) as SessionEvent);
          } catch {
            // ignore malformed frames
          }
        }
      }
    } catch (e: unknown) {
      if (closed) return;
      if (e instanceof Error && e.name === 'AbortError') return;
      onError?.(e instanceof Error ? e : new Error(String(e)));
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
