import { getToken } from './auth';

export interface ActivityEvent {
  id: string;
  timestampMs: number;
  actorUserId?: string | null;
  actorUsername?: string | null;
  action: string;
  target?: string | null;
  metadata?: Record<string, unknown> | null;
}

function authHeaders(): Record<string, string> {
  return {
    Authorization: `Bearer ${getToken()}`,
  };
}

async function asError(res: Response): Promise<never> {
  const msg = await res.text().catch(() => `${res.status}`);
  throw new Error(msg || `${res.status}`);
}

export async function listActivity(
  agentId: string,
  opts: { since?: number; limit?: number } = {},
): Promise<ActivityEvent[]> {
  const params = new URLSearchParams();
  if (opts.since != null) params.set('since', String(opts.since));
  if (opts.limit != null) params.set('limit', String(opts.limit));
  const qs = params.toString();
  const url = `/api/agents/${encodeURIComponent(agentId)}/activity${qs ? `?${qs}` : ''}`;
  const res = await fetch(url, { headers: authHeaders() });
  if (!res.ok) return asError(res);
  return res.json();
}
