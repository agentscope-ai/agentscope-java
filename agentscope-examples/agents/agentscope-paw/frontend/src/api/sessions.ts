export interface InboxEntry {
  sessionKey: string;
  sessionId: string;
  agentId: string;
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
  const res = await fetch(url);
  if (!res.ok) throw new Error('Failed to load inbox');
  return res.json();
}

export async function turns(agentId: string, sessionKey: string): Promise<TurnEntry[]> {
  const res = await fetch(
    `/api/agents/${encodeURIComponent(agentId)}/sessions/${encodeURIComponent(sessionKey)}`,
  );
  if (!res.ok) throw new Error('Failed to fetch session turns');
  return res.json();
}

export async function resetSession(agentId: string, sessionKey: string): Promise<ResetResult> {
  const res = await fetch(
    `/api/agents/${encodeURIComponent(agentId)}/sessions/${encodeURIComponent(sessionKey)}/reset`,
    { method: 'POST' },
  );
  if (!res.ok) throw new Error('Failed to reset session');
  return res.json();
}

export async function markRead(agentId: string, sessionKey: string): Promise<ReadStateResult> {
  const res = await fetch(
    `/api/agents/${encodeURIComponent(agentId)}/sessions/${encodeURIComponent(sessionKey)}/read`,
    { method: 'PATCH' },
  );
  if (!res.ok) throw new Error('Failed to mark session as read');
  return res.json();
}

export async function deleteSession(agentId: string, sessionKey: string): Promise<void> {
  const res = await fetch(
    `/api/agents/${encodeURIComponent(agentId)}/sessions/${encodeURIComponent(sessionKey)}`,
    { method: 'DELETE' },
  );
  if (!res.ok && res.status !== 204) throw new Error('Failed to delete session');
}
