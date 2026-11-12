import { AgentSessionView } from './agentSessionView';
import { authHeaders, readApiError } from './http';
import { managedSessionCreateBody, normalizeManagedSession, type ManagedSession, type ManagedSessionWire, type CreateManagedSessionRequest } from './managedSessions';

/** Agent API v1: opaque cursors belong to one session; committed deltas replay with their records. */
export interface AgentSessionEvent {
  schema_version?: number;
  id?: string;
  type: string;
  session_id: string;
  created_at?: number | string;
  cursor?: string;
  ephemeral?: boolean;
  data: Record<string, unknown>;
}
export interface AgentTurn {
  id: string;
  sessionId: string;
  status: string;
  createdAt: number;
  updatedAt?: number;
  admissionSequence?: number;
  deliveryStatus?: string;
  deliveryError?: string;
  errorCode?: string;
  error?: { code: string; message?: string };
}
export interface AgentSessionSnapshot {
  session: ManagedSession;
  as_of: string;
  items: AgentSessionEvent[];
  turns: AgentSessionEvent[];
  runs: AgentSessionEvent[];
  steps?: AgentSessionEvent[];
  required_actions: AgentSessionEvent[];
  tools?: AgentSessionEvent[];
  inputs?: AgentSessionEvent[];
  action_commands?: AgentSessionEvent[];
  artifacts?: AgentSessionEvent[];
  subagents?: AgentSessionEvent[];
  usage?: Record<string, unknown>;
  resource_pages?: Record<string, AgentResourcePage>;
}
export interface AgentResourcePage {
  snapshot_id: string;
  as_of: string;
  expires_at: number;
  data: AgentSessionEvent[];
  next_cursor: string | null;
  has_more: boolean;
}
interface AgentServiceSnapshot {
  snapshot_id: string;
  as_of: string;
  resources: Record<string, AgentResourcePage>;
  usage: Record<string, unknown>;
}
export interface ActionAnswer {
  request_id: string;
  allow?: boolean;
  reason?: string;
  output?: string;
  is_error?: boolean;
  value?: unknown;
}
const base = '/v1/sessions';
const path = (session: string) => `${base}/${encodeURIComponent(session)}`;
export class AgentRequestError extends Error {
  constructor(message: string, readonly status: number) { super(message); }
}
async function request<T>(url: string, init: RequestInit = {}): Promise<T> {
  const res = await fetch(url, { ...init, headers: { ...authHeaders(), 'Content-Type': 'application/json', 'Managed-Agents-Extensions': 'turn-inputs-v1', ...init.headers } });
  if (!res.ok) throw new AgentRequestError((await readApiError(res, 'Agent API request failed')).message, res.status);
  return res.status === 204 ? undefined as T : res.json() as Promise<T>;
}
export const createAgentSession = async (input: CreateManagedSessionRequest) =>
  normalizeManagedSession(await request<ManagedSessionWire>(base, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify(managedSessionCreateBody(input)) }));
export interface SessionProtocolEvent { id: string; type: string; processed_at: string | null; content?: Record<string, unknown>[]; name?: string; input?: Record<string, unknown>; mcp_server_name?: string; tool_use_id?: string; mcp_tool_use_id?: string; is_error?: boolean; managed_agents?: Record<string, unknown> }
export function decodeSessionEvent(session: string, event: SessionProtocolEvent): AgentSessionEvent {
  const ext = event.managed_agents ?? {};
  if (ext.session_id !== undefined && ext.session_id !== session) throw new Error('Event belongs to another Session');
  const type = event.type.startsWith('managed_agents.') ? event.type.slice('managed_agents.'.length) : event.type;
  const itemId = String(ext.item_id ?? event.id);
  if (event.type === 'user.message' || event.type === 'agent.message') return {
    id: event.id, session_id: session, type: 'item.completed', created_at: String(ext.admitted_at ?? event.processed_at ?? '') || undefined,
    data: { ...ext, item_id: itemId, item: { id: itemId, role: event.type === 'user.message' ? 'user' : 'assistant', content: event.content ?? [], status: event.processed_at ? 'completed' : 'accepted' } },
  };
  if (['agent.tool_use', 'agent.mcp_tool_use'].includes(event.type)) return { id: event.id, session_id: session, type: 'tool.requested', created_at: event.processed_at ?? undefined,
    data: { ...ext, tool_call_id: ext.tool_call_id ?? event.id, name: event.name, input: event.input, status: 'requested', mcp_server_name: event.mcp_server_name } };
  if (['agent.tool_result', 'agent.mcp_tool_result'].includes(event.type)) return { id: event.id, session_id: session, type: 'tool.completed', created_at: event.processed_at ?? undefined,
    data: { ...ext, tool_call_id: ext.tool_call_id ?? event.mcp_tool_use_id ?? event.tool_use_id, output: event.content ?? [], status: event.is_error ? 'failed' : 'completed' } };
  if (type === 'item.finalized') return { id: event.id, session_id: session, type: 'item.output_committed', data: ext };
  return { id: event.id, session_id: session, type, created_at: event.processed_at ?? undefined, data: ext };
}
export const getAgentSessionSnapshot = async (session: string, signal?: AbortSignal): Promise<AgentSessionSnapshot> => {
  const metadata = normalizeManagedSession(await request<ManagedSessionWire>(path(session), { signal }));
  const snapshot: AgentSessionSnapshot = { session: metadata, as_of: '', items: [], turns: [], runs: [], tools: [], inputs: [], required_actions: [], usage: {} };
  const reducer = new AgentSessionView(snapshot);
  let count = 0;
  let page: string | null = null;
  do {
    const result: { data: SessionProtocolEvent[]; next_page: string | null } = await request(`${path(session)}/events?` + new URLSearchParams({ limit: '200', order: 'asc', ...(page ? { page } : {}) }), { signal });
    if (!Array.isArray(result.data) || !('next_page' in result)) throw new Error('Unsupported Session history schema');
    for (const wire of result.data) {
      if (++count > 8000) throw new Error('Session history exceeds the display limit');
      reducer.apply(decodeSessionEvent(session, wire));
    }
    page = result.next_page;
  } while (page);
  return reducer.snapshot();
};
function flattenSnapshot<T>(metadata: T, snapshot: AgentServiceSnapshot): Omit<AgentSessionSnapshot, 'session'> & { session: T } {
  if (!snapshot.snapshot_id || !snapshot.resources || typeof snapshot.as_of !== 'string')
    throw new Error('Unsupported Session snapshot schema; use the matching Service and client version.');
  const rows = (kind: string) => snapshot.resources[kind]?.data ?? [];
  return { session: metadata, as_of: snapshot.as_of, items: rows('items'), turns: rows('turns'), runs: rows('runs'),
    steps: rows('steps'), required_actions: rows('required_actions'), tools: rows('tools'), inputs: rows('inputs'),
    action_commands: rows('action_commands'), artifacts: rows('artifacts'), subagents: rows('subagents'),
    usage: { ...snapshot.usage, authoritative: true }, resource_pages: snapshot.resources };
}

export const getAgentResourcePage = (session: string, kind: string, page: AgentResourcePage, signal?: AbortSignal) =>
  request<AgentResourcePage>(`${path(session)}/resources/${encodeURIComponent(kind)}?${new URLSearchParams({
    snapshot_id: page.snapshot_id, after: page.next_cursor ?? '', limit: '200',
  })}`, { signal });
export const listAgentSessionEvents = (session: string, after?: string, limit = 100) =>
  request<{ data: AgentSessionEvent[]; next_cursor: string; has_more: boolean }>(
    `${path(session)}/events?${new URLSearchParams({ ...(after ? { after } : {}), limit: String(limit) })}`);
interface AgentTurnWire {
  id: string; session_id: string; status: string; created_at: string;
  admission_sequence: number; delivery_status: string;
}
function normalizeTurn(turn: AgentTurnWire): AgentTurn {
  return { id: turn.id, sessionId: turn.session_id, status: turn.status,
    createdAt: Date.parse(turn.created_at), admissionSequence: turn.admission_sequence, deliveryStatus: turn.delivery_status };
}
export const listAgentTurns = async (session: string) => (await listAgentTurnPage(session)).data;
export interface AgentTurnPage { data: AgentTurn[]; has_more: boolean; next_cursor: string | null }
export const listAgentTurnPage = async (session: string, after?: string, status?: string, signal?: AbortSignal): Promise<AgentTurnPage> => {
  const page = await request<{ data: AgentTurnWire[]; has_more: boolean; next_cursor: string | null }>(`${path(session)}/turns?${new URLSearchParams({ limit: status ? '1' : '200', order: 'desc',
    ...(after ? { after } : {}), ...(status ? { status } : {}) })}`, { signal });
  return { ...page, data: page.data.map(normalizeTurn) };
};
export const getAgentTurn = async (session: string, turn: string, signal?: AbortSignal) =>
  normalizeTurn(await request<AgentTurnWire>(`${path(session)}/turns/${encodeURIComponent(turn)}`, { signal }));
export interface AgentTurnCapabilities {
  runtime_kind: string;
  configuration_revision: string;
  available_commands: string[];
  supported_commands: Record<string, boolean>;
  checkpoint_restore: boolean;
  action_scope: string;
  files: boolean;
  file_input: boolean;
  artifacts: boolean;
}
export interface AgentSessionCapabilities {
  session_id: string; runtime_kind: string; configuration_revision: string;
  capabilities: { files: boolean; file_input: boolean; artifacts: boolean; budget?: boolean;
    checkpoints?: boolean; checkpoint_restore?: boolean; fork?: boolean };
}
export const getAgentSessionCapabilities = async (session: string, signal?: AbortSignal) => {
  const value = await request<AgentSessionCapabilities>(`${path(session)}/capabilities`, { signal });
  if (!value.capabilities || ['files', 'file_input', 'artifacts'].some(name =>
    typeof value.capabilities[name as keyof AgentSessionCapabilities['capabilities']] !== 'boolean'))
    throw new Error('Unsupported Session capabilities schema; use the matching Service and client version.');
  return value;
};
export const getAgentTurnCapabilities = (session: string, turn: string, signal?: AbortSignal) =>
  request<AgentTurnCapabilities>(`${path(session)}/turns/${encodeURIComponent(turn)}/capabilities`, { signal });
/** An older registry page must not replace a status already refreshed from a live event. */
export function mergeAgentTurns(previous: AgentTurn[], incoming: AgentTurn[], fresh = false): AgentTurn[] {
  const merged = new Map(previous.map(turn => [turn.id, turn]));
  for (const turn of incoming) {
    const old = merged.get(turn.id);
    if (!old || (turn.updatedAt ?? turn.createdAt) > (old.updatedAt ?? old.createdAt)
        || fresh && (turn.updatedAt ?? turn.createdAt) === (old.updatedAt ?? old.createdAt)) merged.set(turn.id, turn);
  }
  if (merged.size > 2000) throw new Error('Turn display limit reached. Refresh to view the latest turns.');
  return [...merged.values()].sort((a, b) => (b.admissionSequence ?? b.createdAt) - (a.admissionSequence ?? a.createdAt));
}

export type AgentInput = { message: string } | { input: Array<{ role: 'user'; content: Record<string, unknown>[] }> };
function inputContent(input: string | AgentInput): Record<string, unknown>[] {
  if (typeof input === 'string') return [{ type: 'text', text: input }];
  if ('message' in input) return [{ type: 'text', text: input.message }];
  if (input.input.length !== 1) throw new Error('A single user message is required');
  return input.input[0].content;
}
async function sendSessionEvent(session: string, event: Record<string, unknown>, key: string) {
  const result = await request<{ data: SessionProtocolEvent[] }>(`${path(session)}/events`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify({ events: [event] }) });
  const accepted = result.data[0], ext = accepted.managed_agents!;
  return { ...(ext.receipt as { id: string; status: string }), event_id: accepted.id, turn_id: String(ext.turn_id), input_type: String(ext.intent), created_at: String(ext.admitted_at ?? ''), delivery_status: 'pending' };
}
export const submitAgentTurn = async (session: string, message: string | AgentInput, idempotencyKey: string): Promise<AgentTurn> => {
  const receipt = await sendSessionEvent(session, { type: 'user.message', content: inputContent(message), managed_agents: { intent: 'new_turn' } }, idempotencyKey);
  return { id: receipt.id, sessionId: session, status: receipt.status, createdAt: Date.parse(receipt.created_at) || 0 };
};
export interface AgentInputReceipt { id: string; turn_id: string; input_type: string; status: string; delivery_status: string; rejection_reason?: string }
function submitInput(session: string, turn: string, type: string, payload: unknown, key: string, action?: string): Promise<AgentInputReceipt> {
  if (!['steer', 'inject', 'cancel'].includes(type) || action) return Promise.reject(new Error('unsupported_capability: Tool replies are unavailable'));
  return sendSessionEvent(session, { type: type === 'cancel' ? 'user.interrupt' : 'user.message', ...(type === 'cancel' ? {} : { content: inputContent(payload as AgentInput) }), managed_agents: { intent: type, turn_id: turn } }, key);
}
export const cancelAgentTurn = (session: string, turn: string, key: string = crypto.randomUUID()) =>
  submitInput(session, turn, 'cancel', {}, key);
export const answerAgentActions = async (session: string, turn: string, answers: ActionAnswer[], key: string) => {
  for (const answer of answers) {
    const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(JSON.stringify([key, answer.request_id])));
    const inputKey = [...new Uint8Array(digest)].map(byte => byte.toString(16).padStart(2, '0')).join('');
    const payload = answer.allow === undefined ? { output: answer.output ?? '', is_error: answer.is_error ?? false } : { allow: answer.allow, ...(answer.reason ? { reason: answer.reason } : {}) };
    await submitInput(session, turn, 'reply', payload, inputKey, answer.request_id);
  }
};
/** Keep JSON text intact when it contains integers outside the JavaScript Number range. */
export const answerAgentSignal = (session: string, turn: string, requestId: string, valueJson: string, key: string) => {
  JSON.parse(valueJson);
  return submitInput(session, turn, 'reply', { output: valueJson }, key, requestId);
};
export const steerAgentTurn = (session: string, turn: string, input: AgentInput, key: string) =>
  submitInput(session, turn, 'steer', input, key);
export const injectAgentContext = (session: string, turn: string, input: AgentInput, key: string) =>
  submitInput(session, turn, 'inject', input, key);
export interface AgentToolTask {
  task_id: string; turn_id: string; status: string; result_status: string;
  authorization_generation?: number; updated_at?: number; tool_name: string; environment_type?: string;
  workspace_generation?: number; workspace_status?: string; workspace_busy?: boolean;
  workspace_failure?: string; workspace_snapshot_pending?: boolean; failure_code?: string;
}
export const listAgentToolTasks = (session: string, turn: string, after?: string, signal?: AbortSignal) =>
  request<{ data: AgentToolTask[]; next_cursor: string | null }>(`${path(session)}/tool-tasks?${new URLSearchParams({
    turn, limit: '100', ...(after ? { after } : {}),
  })}`, { signal });
export const recoverAgentTool = (session: string, turn: string, task: AgentToolTask, key: string) =>
  request<{ command_id: string }>(`${path(session)}/turns/${encodeURIComponent(turn)}/tool-recovery`, {
    method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify({ task_id: task.task_id,
      authorization_generation: task.authorization_generation, confirm_side_effects: true }),
  });
export interface WorkspaceRecovery {
  mode: 'reconnect' | 'restore' | 'discard'; provider_id?: string;
  confirm_side_effects: boolean; confirm_provider_idle?: boolean;
  confirm_provider_absent?: boolean; confirm_data_loss?: boolean;
}
export const recoverAgentWorkspace = (session: string, turn: string, task: AgentToolTask, recovery: WorkspaceRecovery, key: string) =>
  request<{ command_id: string }>(`${path(session)}/turns/${encodeURIComponent(turn)}/workspace-recovery`, {
    method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify({ ...recovery,
      task_id: task.task_id, authorization_generation: task.authorization_generation, workspace_generation: task.workspace_generation }),
  });
export const getAgentCommand = (session: string, command: string, signal?: AbortSignal) =>
  request<{ command_id: string; command_status: string; failure_code?: string }>(`${path(session)}/commands/${encodeURIComponent(command)}`, { signal });
export const listAgentActionCommands = (session: string, turn: string) =>
  request<Array<Record<string, unknown>>>(`${path(session)}/turns/${encodeURIComponent(turn)}/actions`);
export interface AgentFile { file_id: string; name: string; media_type: string; size: number; sha256: string; download_url: string }
export const uploadAgentFile = async (session: string, file: File, key: string): Promise<AgentFile> => {
  const value = await request<AgentFile>(`${path(session)}/files`, {
    method: 'POST', headers: { 'Idempotency-Key': key, 'X-File-Name': encodeURIComponent(file.name), 'Content-Type': file.type || 'application/octet-stream' }, body: file,
  });
  return value;
};
export async function downloadAgentFile(session: string, fileId: string): Promise<Blob> {
  const response = await fetch(`${path(session)}/files/${encodeURIComponent(fileId)}/content`, { headers: authHeaders() });
  if (!response.ok) throw await readApiError(response, 'File download failed');
  return response.blob();
}
export interface AgentArtifact {
  artifact_id: string; name: string; media_type: string; sha256?: string;
  file_id?: string; uri?: string; download_url?: string; size?: number;
}
export const publishAgentArtifact = (session: string, fileId: string, key: string) =>
  request<AgentArtifact>(`${path(session)}/artifacts`, { method: 'POST',
    headers: { 'Idempotency-Key': key }, body: JSON.stringify({ file_id: fileId }) });
export const deleteAgentArtifact = (session: string, id: string) =>
  request<void>(`${path(session)}/artifacts/${encodeURIComponent(id)}`, { method: 'DELETE' });
export async function downloadAgentArtifact(session: string, id: string): Promise<Blob> {
  const response = await fetch(`${path(session)}/artifacts/${encodeURIComponent(id)}/content`, { headers: authHeaders() });
  if (!response.ok) throw await readApiError(response, 'Artifact download failed');
  return response.blob();
}
export type AgentSubagentSnapshot = Omit<AgentSessionSnapshot, 'session'> & { session: { id: string; parent_session_id: string } };
export const getAgentSubagentSnapshot = async (session: string, child: string, signal?: AbortSignal): Promise<AgentSubagentSnapshot> =>
  flattenSnapshot({ id: child, parent_session_id: session }, await request<AgentServiceSnapshot>(
    `${path(child)}/snapshot?limit=200`, { signal }));
export const getAgentUsage = (session: string) =>
  request<{ data: Record<string, unknown>; as_of: string }>(`${path(session)}/usage`);
export interface AgentCheckpoint { checkpoint_id: string; workspace_available: boolean; created_at: number; turn_id?: string | null }
export interface AgentCheckpointPage { data: AgentCheckpoint[]; as_of: string; next_cursor: string | null; has_more: boolean }
export interface AgentCheckpointCommand { command_id: string; operation_id: string; command_status: string;
  source_session_id: string; session_id: string; error?: { code: string }; workspace_required?: boolean }
export const listAgentCheckpoints = (session: string, after?: string, through?: string, signal?: AbortSignal) =>
  request<AgentCheckpointPage>(`${path(session)}/checkpoints?${new URLSearchParams({ limit: '20',
    ...(after ? { after } : {}), ...(through ? { through } : {}) })}`, { signal });
export const restoreAgentCheckpoint = (session: string, checkpointId: string, key: string) =>
  request<AgentCheckpointCommand>(`${path(session)}/checkpoints/restore`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify({ checkpoint_id: checkpointId }) });
export const forkAgentSession = (session: string, targetSession: string, checkpointId: string, key: string) =>
  request<AgentCheckpointCommand>(`${path(session)}/fork`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify({ target_session_id: targetSession, checkpoint_id: checkpointId }) });
export const getAgentCheckpointOperation = async (session: string, operation: string, signal?: AbortSignal) => {
  const receipt = await request<AgentCheckpointCommand>(`${path(session)}/checkpoint-operations/${encodeURIComponent(operation)}`, { signal });
  if (receipt.source_session_id !== session || receipt.operation_id !== operation) throw new Error('Recovery receipt belongs to another operation.');
  return receipt;
};
export const cancelAgentCheckpointOperation = (session: string, operation: string) =>
  request<AgentCheckpointCommand>(`${path(session)}/checkpoint-operations/${encodeURIComponent(operation)}/cancel`, { method: 'POST' });
export interface AgentBudgetLimits { max_model_calls?: number; max_total_tokens?: number; max_cost?: number | string; currency?: string }
export interface AgentBudgetSettings { revision: number; limits: AgentBudgetLimits }
export interface AgentBudget extends AgentBudgetSettings { root_session_id: string; configuration_revision?: number }
export const setAgentBudget = (session: string, limits: AgentBudgetLimits, revision?: number) =>
  request<AgentBudgetSettings>(`${path(session)}/budget`, { method: 'PUT',
    headers: revision === undefined ? {} : { 'If-Match': String(revision) }, body: JSON.stringify(limits) });
export const getAgentBudget = (session: string, signal?: AbortSignal) => request<AgentBudget>(`${path(session)}/budget`, { signal });
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
  onReady?: () => Promise<void>;
  onUnavailable?: () => Promise<void>;
  onConnection?: (state: 'connected' | 'reconnecting') => void;
}): Promise<void> {
  if (options.after) throw new Error('unsupported_capability: SSE resume is unavailable');
  let delay = 500;
  while (!options.signal.aborted) {
    let reader: ReadableStreamDefaultReader<Uint8Array> | undefined;
    try {
      if (options.preview) throw new Error('unsupported_capability: Event deltas are unavailable');
      const res = await fetch(`${path(session)}/events/stream`, {
        headers: { ...authHeaders(), Accept: 'text/event-stream', 'Managed-Agents-Extensions': 'turn-inputs-v1' }, signal: options.signal,
      });
      if (!res.ok) throw new AgentStreamError((await readApiError(res, 'Event stream failed')).message, res.status);
      if (!res.body) throw new Error('Event stream has no body');
      await options.onReady?.();
      options.onConnection?.('connected');
      reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '', data: string[] = [];
      const emit = async () => {
        if (data.length) {
          const wire = JSON.parse(data.join('\n')) as SessionProtocolEvent;
          const event = decodeSessionEvent(session, wire);
          if (event.session_id !== session || (event.schema_version !== undefined && event.schema_version !== 1))
            throw new AgentStreamError('Unsupported event schema or session scope', 400);
          // Advance only after successful application; unknown informational types are still consumable.
          await options.onEvent(event);
          // Reconnection restores history; this stream has no durable cursor.
        }
        data = [];
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
          else if (line.startsWith('id:')) { /* Standard stream has no resume position. */ }
        }
      }
    } catch (error) {
      if (options.signal.aborted) return;
      // Auth, bad/expired cursors and incompatible schemas need caller intervention (usually a fresh snapshot).
      if (error instanceof AgentStreamError && error.status >= 400 && error.status < 500 && error.status !== 429) throw error;
      if (!(error instanceof TypeError) && !(error instanceof AgentStreamError)) throw error;
      await options.onUnavailable?.();
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
  while (!signal.aborted) {
    const turn = await getAgentTurn(session, turnId, signal);
    if (['completed', 'failed', 'cancelled'].includes(turn.status)) return { session_id: session, type: 'turn.' + turn.status, data: { turn_id: turnId, status: turn.status } };
    await pause(250, signal);
  }
  throw new DOMException('Waiting for Turn was aborted; execution continues', 'AbortError');
}

export interface AgentTurnOutput { turn_status: string; output_available: boolean; data: SessionProtocolEvent[]; next_cursor: string | null; has_more: boolean }
export const getAgentTurnOutput = (session: string, turn: string, after = '', signal?: AbortSignal) => request<AgentTurnOutput>(`${path(session)}/turns/${encodeURIComponent(turn)}/output?` + new URLSearchParams({ limit: '100', ...(after ? { after } : {}) }), { signal });
