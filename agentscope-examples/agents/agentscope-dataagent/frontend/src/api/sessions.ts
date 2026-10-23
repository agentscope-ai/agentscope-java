import { getToken } from './auth';

function authHeaders(): Record<string, string> {
  const token = getToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

// ── Agent-scoped session inbox / turns (claw-style) ──────────────────

export interface InboxEntry {
  sessionKey: string;
  sessionId: string;
  agentId: string;
  /**
   * Conversation id used as the URL `?session=` and the body `sessionKey` for chat requests.
   * Null for legacy single-session entries; callers should fall back to `sessionKey` in that case
   * (which the backend will also accept on session-management endpoints).
   */
  conversationId: string | null;
  label: string | null;
  lastActivityMs: number;
  lastMessage: string | null;
  unread: boolean;
}

export interface InboxOptions {
  limit?: number;
  unreadOnly?: boolean;
}

export interface TurnEntry {
  id: string;
  parentId: string | null;
  role: 'USER' | 'ASSISTANT' | 'TOOL' | string;
  content: string | null;
  timestampMs: number;
  toolName: string | null;
  toolInput: string | null;
  toolResult: string | null;
}

export interface ResetResult {
  sessionKey: string;
  reset: boolean;
}

export interface ReadStateResult {
  sessionKey: string;
  readAtMs: number;
  unread: boolean;
}

export async function inbox(agentId: string, opts: InboxOptions = {}): Promise<InboxEntry[]> {
  const params = new URLSearchParams();
  if (opts.limit != null) params.set('limit', String(opts.limit));
  if (opts.unreadOnly) params.set('unreadOnly', 'true');
  const qs = params.toString();
  const url = `/api/agents/${encodeURIComponent(agentId)}/sessions/inbox${qs ? `?${qs}` : ''}`;
  const res = await fetch(url, { headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to load inbox');
  return res.json();
}

export async function turns(agentId: string, sessionKey: string): Promise<TurnEntry[]> {
  const res = await fetch(
    `/api/agents/${encodeURIComponent(agentId)}/sessions/${encodeURIComponent(sessionKey)}`,
    { headers: authHeaders() },
  );
  if (!res.ok) throw new Error('Failed to fetch session turns');
  return res.json();
}

export async function resetSession(agentId: string, sessionKey: string): Promise<ResetResult> {
  const res = await fetch(
    `/api/agents/${encodeURIComponent(agentId)}/sessions/${encodeURIComponent(sessionKey)}/reset`,
    { method: 'POST', headers: authHeaders() },
  );
  if (!res.ok) throw new Error('Failed to reset session');
  return res.json();
}

export async function markRead(agentId: string, sessionKey: string): Promise<ReadStateResult> {
  const res = await fetch(
    `/api/agents/${encodeURIComponent(agentId)}/sessions/${encodeURIComponent(sessionKey)}/read`,
    { method: 'PATCH', headers: authHeaders() },
  );
  if (!res.ok) throw new Error('Failed to mark session as read');
  return res.json();
}

export async function deleteSession(agentId: string, sessionKey: string): Promise<void> {
  const res = await fetch(
    `/api/agents/${encodeURIComponent(agentId)}/sessions/${encodeURIComponent(sessionKey)}`,
    { method: 'DELETE', headers: authHeaders() },
  );
  if (!res.ok && res.status !== 204) throw new Error('Failed to delete session');
}

// ── Legacy admin-side session listing (used by admin/AgentSidebar) ───

export interface SessionView {
  sessionKey: string;
  agentId: string;
  sessionId: string;
  label: string | null;
  kind: string;
  lastActivityMs: number;
  createdAtMs: number;
  userId: string | null;
}

export async function listSessions(limit = 50): Promise<SessionView[]> {
  const res = await fetch(`/api/sessions?limit=${limit}`, { headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to list sessions');
  return res.json();
}
