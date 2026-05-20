const BASE = '';

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

export interface HistoryResult {
  sessionKey: string | null;
  sessionFilePath: string | null;
  content: string | null;
  error: string | null;
}

function authHeaders(): Record<string, string> {
  const token = localStorage.getItem('claw_token');
  return token ? { Authorization: `Bearer ${token}` } : {};
}

export async function listSessions(limit = 50): Promise<SessionView[]> {
  const res = await fetch(`${BASE}/api/sessions?limit=${limit}`, {
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error('Failed to list sessions');
  return res.json();
}

export async function sessionHistory(key: string): Promise<HistoryResult> {
  const encodedKey = encodeURIComponent(key);
  const res = await fetch(`${BASE}/api/sessions/${encodedKey}/history`, {
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error('Failed to fetch history');
  return res.json();
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

export async function sessionTurns(key: string): Promise<TurnEntry[]> {
  const encodedKey = encodeURIComponent(key);
  const res = await fetch(`${BASE}/api/sessions/${encodedKey}/turns`, {
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error('Failed to fetch session turns');
  return res.json();
}

export interface ResetResult {
  sessionKey: string;
  reset: boolean;
}

export async function resetSession(key: string): Promise<ResetResult> {
  const encodedKey = encodeURIComponent(key);
  const res = await fetch(`${BASE}/api/sessions/${encodedKey}/reset`, {
    method: 'POST',
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error('Failed to reset session');
  return res.json();
}
