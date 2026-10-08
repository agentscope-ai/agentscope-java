import { authHeaders, readApiError } from './http';
import type { ManagedSession, CreateManagedSessionRequest } from './managedSessions';

/** Agent API v1: opaque cursors belong to one session; committed deltas replay with their records. */
export interface AgentSessionEvent {
  schema_version?: number;
  id?: string;
  type: string;
  session_id: string;
  created_at?: number;
  cursor?: string;
  ephemeral?: boolean;
  data: Record<string, unknown>;
}
export interface AgentTurn {
  id: string;
  sessionId: string;
  status: string;
  createdAt: number;
  errorCode?: string;
  error?: { code: string; message?: string };
}
export interface AgentSessionSnapshot {
  session: ManagedSession;
  as_of: string;
  items: AgentSessionEvent[];
  turns: AgentSessionEvent[];
  runs: AgentSessionEvent[];
  required_actions: AgentSessionEvent[];
  tools?: AgentSessionEvent[];
  inputs?: AgentSessionEvent[];
  action_commands?: AgentSessionEvent[];
  artifacts?: AgentSessionEvent[];
  subagents?: AgentSessionEvent[];
  usage?: Record<string, unknown>;
}
export interface ActionAnswer {
  request_id: string;
  allow?: boolean;
  reason?: string;
  output?: string;
  is_error?: boolean;
}
const base = '/api/v1/agent-sessions';
const path = (session: string) => `${base}/${encodeURIComponent(session)}`;
async function request<T>(url: string, init: RequestInit = {}): Promise<T> {
  const res = await fetch(url, { ...init, headers: { ...authHeaders(), ...init.headers } });
  if (!res.ok) throw await readApiError(res, 'Agent API request failed');
  return res.status === 204 ? undefined as T : res.json() as Promise<T>;
}
export const createAgentSession = (input: CreateManagedSessionRequest) =>
  request<ManagedSession>(base, { method: 'POST', body: JSON.stringify(input) });
export const getAgentSessionSnapshot = (session: string, signal?: AbortSignal) =>
  request<AgentSessionSnapshot>(`${path(session)}/snapshot`, { signal });
export const listAgentSessionEvents = (session: string, after?: string, limit = 100) =>
  request<{ data: AgentSessionEvent[]; next_cursor: string; has_more: boolean }>(
    `${path(session)}/events?${new URLSearchParams({ ...(after ? { after } : {}), limit: String(limit) })}`);
export const listAgentTurns = async (session: string) => (await request<{ items: AgentTurn[] }>(`${path(session)}/turns?limit=200`)).items;
export type AgentInput = { message: string } | { input: Array<{ role: 'user'; content: Record<string, unknown>[] }> };
export const submitAgentTurn = (session: string, message: string | AgentInput, idempotencyKey: string) =>
  request<AgentTurn>(`${path(session)}/turns`, {
    method: 'POST', headers: { 'Idempotency-Key': idempotencyKey }, body: JSON.stringify(typeof message === 'string' ? { message } : message),
  });
export const cancelAgentTurn = (session: string, turn: string) =>
  request<AgentTurn>(`${path(session)}/turns/${encodeURIComponent(turn)}/cancel`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() } });
export const resumeAgentTurn = (session: string, turn: string) =>
  request<AgentTurn>(`${path(session)}/turns/${encodeURIComponent(turn)}/resume`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() } });
export const answerAgentActions = async (session: string, turn: string, answers: ActionAnswer[], key: string) => {
  for (const [index, answer] of answers.entries()) {
    const { request_id, ...payload } = answer;
    await request(`${path(session)}/turns/${encodeURIComponent(turn)}/actions`, {
      method: 'POST', headers: { 'Idempotency-Key': `${key}-${index}` }, body: JSON.stringify({ request_id, payload }),
    });
  }
};

export const steerAgentTurn = (session: string, turn: string, input: AgentInput, key: string) =>
  request<{ input_id: string; turn_id: string; status: string }>(`${path(session)}/turns/${encodeURIComponent(turn)}/steer`, {
    method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(input),
  });
export const injectAgentContext = (session: string, input: AgentInput, key: string) =>
  request<{ input_id: string; status: string }>(`${path(session)}/inputs/inject`, {
    method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify(input),
  });
export const listAgentActionCommands = (session: string, turn: string) =>
  request<Array<Record<string, unknown>>>(`${path(session)}/turns/${encodeURIComponent(turn)}/actions`);
export interface AgentFile { file_id: string; name: string; media_type: string; size: number; sha256: string; download_url: string }
export const uploadAgentFile = async (session: string, file: File, key: string): Promise<AgentFile> => {
  const value = await request<{ id: string; name: string; content_type: string; size: number; checksum: string }>(`${path(session)}/files`, {
    method: 'POST', headers: { 'Idempotency-Key': key, 'X-File-Name': encodeURIComponent(file.name), 'Content-Type': file.type || 'application/octet-stream' }, body: file,
  });
  return { file_id: value.id, name: value.name, media_type: value.content_type, size: value.size, sha256: value.checksum, download_url: `${path(session)}/files/${value.id}/content` };
};
export async function downloadAgentFile(session: string, fileId: string): Promise<Blob> {
  const response = await fetch(`${path(session)}/files/${encodeURIComponent(fileId)}/content`, { headers: authHeaders() });
  if (!response.ok) throw await readApiError(response, 'File download failed');
  return response.blob();
}
export type AgentSubagentSnapshot = Omit<AgentSessionSnapshot, 'session'> & { session: { id: string; parent_session_id: string } };
export const getAgentSubagentSnapshot = (session: string, child: string, signal?: AbortSignal) =>
  request<AgentSubagentSnapshot>(`${path(session)}/subagents/${encodeURIComponent(child)}/snapshot`, { signal });
export const getAgentUsage = (session: string, includeChildren = false) =>
  request<{ data: Record<string, unknown>; as_of: string }>(`${path(session)}/usage?include_children=${includeChildren}`);
export const listAgentCheckpoints = (session: string, after?: string) =>
  request<{ data: Array<{ checkpoint_id: string; reason?: string; created_at: number }>; next_cursor?: string; has_more: boolean }>(
    `${path(session)}/checkpoints?${new URLSearchParams(after ? { after } : {})}`);
export const restoreAgentCheckpoint = (session: string, checkpointId: string, reason: string, key: string) =>
  request(`${path(session)}/checkpoints/restore`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify({ checkpoint_id: checkpointId, reason }) });
export const forkAgentSession = (session: string, targetSession: string, checkpointId: string, reason: string, key: string) =>
  request(`${path(session)}/fork`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify({ target_session_id: targetSession, checkpoint_id: checkpointId, reason }) });
export const setAgentBudget = (session: string, limits: { max_model_calls?: number; max_total_tokens?: number; max_cost?: number; currency?: string }) =>
  request(`${path(session)}/budget`, { method: 'PUT', body: JSON.stringify(limits) });
export const getAgentBudget = (session: string) => request<Record<string, unknown>>(`${path(session)}/budget`);
export const registerAgentWebhook = (session: string, url: string, eventTypes: string[], key: string) =>
  request<Record<string, unknown>>(`${path(session)}/webhooks`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify({ url, event_types: eventTypes }) });
export const listAgentWebhooks = (session: string) => request<{ data: Record<string, unknown>[] }>(`${path(session)}/webhooks`);
export const retryAgentWebhook = (session: string, id: string) => request(`${path(session)}/webhooks/${encodeURIComponent(id)}/retry`, { method: 'POST' });
export const deleteAgentWebhook = (session: string, id: string) => request(`${path(session)}/webhooks/${encodeURIComponent(id)}`, { method: 'DELETE' });

export class AgentStreamError extends Error {
  constructor(message: string, readonly status: number) { super(message); }
}
function pause(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise(resolve => {
    const finish = () => { clearTimeout(timer); signal.removeEventListener('abort', finish); resolve(); };
    const timer = setTimeout(finish, ms);
    signal.addEventListener('abort', finish, { once: true });
    if (signal.aborted) finish();
  });
}

/** Reconnect after transport loss. EOF is never a completed turn; abort only closes this reader. */
export async function streamAgentSession(session: string, options: {
  signal: AbortSignal;
  after?: string;
  preview?: boolean;
  onEvent: (event: AgentSessionEvent) => void | Promise<void>;
  onConnection?: (state: 'connected' | 'reconnecting') => void;
}): Promise<void> {
  let cursor = options.after;
  let delay = 500;
  while (!options.signal.aborted) {
    let reader: ReadableStreamDefaultReader<Uint8Array> | undefined;
    try {
      const query = new URLSearchParams({ preview: String(!!options.preview) });
      if (cursor) query.set('after', cursor);
      const res = await fetch(`${path(session)}/events/stream?${query}`, {
        headers: { ...authHeaders(), Accept: 'text/event-stream' }, signal: options.signal,
      });
      if (!res.ok) throw new AgentStreamError((await readApiError(res, 'Event stream failed')).message, res.status);
      if (!res.body) throw new Error('Event stream has no body');
      options.onConnection?.('connected');
      reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '', data: string[] = [], eventId: string | undefined;
      const emit = async () => {
        if (data.length) {
          const event = JSON.parse(data.join('\n')) as AgentSessionEvent;
          if (event.session_id !== session || (event.schema_version !== undefined && event.schema_version !== 1))
            throw new AgentStreamError('Unsupported event schema or session scope', 400);
          // Advance only after successful application; unknown informational types are still consumable.
          await options.onEvent(event);
          if (!event.ephemeral && eventId) cursor = eventId;
        }
        data = []; eventId = undefined;
      };
      while (!options.signal.aborted) {
        const chunk = await reader.read();
        if (chunk.done) break;
        buffer += decoder.decode(chunk.value, { stream: true });
        if (buffer.length > 16 * 1024 * 1024) throw new AgentStreamError('SSE frame exceeds client limit', 413);
        let newline: number;
        while ((newline = buffer.indexOf('\n')) >= 0) {
          const line = buffer.slice(0, newline).replace(/\r$/, ''); buffer = buffer.slice(newline + 1);
          if (!line) { await emit(); delay = 500; }
          else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
          else if (line.startsWith('id:')) eventId = line.slice(3).trim();
        }
      }
    } catch (error) {
      if (options.signal.aborted) return;
      // Auth, bad/expired cursors and incompatible schemas need caller intervention (usually a fresh snapshot).
      if (error instanceof AgentStreamError && error.status >= 400 && error.status < 500 && error.status !== 429) throw error;
      if (!(error instanceof TypeError) && !(error instanceof AgentStreamError)) throw error;
    } finally { await reader?.cancel().catch(() => {}); reader?.releaseLock(); }
    if (!options.signal.aborted) {
      options.onConnection?.('reconnecting'); await pause(delay, options.signal); delay = Math.min(delay * 2, 10000);
    }
  }
}

/** Execution endings (including historical turn.ended) never determine a logical turn outcome. */
export function isAgentTurnOutcome(event: AgentSessionEvent, turnId: string): boolean {
  return event.data.turn_id === turnId &&
    ['turn.completed', 'turn.failed', 'turn.cancelled', 'turn.interrupted', 'turn.requires_action'].includes(event.type);
}

/** Root-turn helper. Suspended/failed/interrupted are explicit outcomes, never inferred success. */
export async function waitForAgentTurn(session: string, turnId: string, signal: AbortSignal): Promise<AgentSessionEvent> {
  const terminal = (event: AgentSessionEvent) => isAgentTurnOutcome(event, turnId);
  const snapshot = await getAgentSessionSnapshot(session, signal);
  const existing = snapshot.turns.find(terminal);
  if (existing) return existing;
  const controller = new AbortController();
  const abort = () => controller.abort(); signal.addEventListener('abort', abort, { once: true });
  if (signal.aborted) controller.abort();
  let result: AgentSessionEvent | undefined;
  try {
    await streamAgentSession(session, { after: snapshot.as_of, signal: controller.signal,
      onEvent: event => { if (terminal(event)) { result = event; controller.abort(); } } });
    if (!result) throw new DOMException('Waiting for turn was aborted', 'AbortError');
    return result;
  } finally { signal.removeEventListener('abort', abort); }
}
