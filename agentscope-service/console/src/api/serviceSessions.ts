import type { AgentResourcePage } from './agentSessions';
import { toolProgress } from './toolProgress';
/** Public service client shared by Agent, Team and Workflow Sessions. */
export type ServiceData = Record<string, unknown>;
export interface ServiceEvent {
  schema_version: number; id: string; type: string; turn_id: string;
  created_at: number; cursor: string; data: ServiceData;
}
export interface ServiceSnapshot {
  as_of: string; turn: ServiceData;
  items: Record<string, ServiceData>; tools: Record<string, ServiceData>;
  required_actions: Record<string, ServiceData>; steps: Record<string, ServiceData>;
  artifacts: Record<string, ServiceData>; usage: Record<string, ServiceData>;
  resource_pages?: Record<string, AgentResourcePage>; usage_summary?: ServiceData;
}
export interface ServiceSubmission {
  id: string; sessionId: string; status: string;
  statusUrl: string; snapshotUrl: string; eventsUrl: string;
}
export const terminalTurn = (status: unknown) => ['completed', 'partial_succeeded', 'failed', 'cancelled', 'timed_out', 'interrupted'].includes(String(status));
export class ServiceAPIError extends Error {
  constructor(public status: number, public detail: ServiceData) { super(`Service API ${status}: ${JSON.stringify(detail)}`); }
}
export class ServiceProtocolError extends Error {}
async function serviceError(response: Response) {
  const text = await response.text();
  let detail: ServiceData;
  try { detail = JSON.parse(text) as ServiceData; } catch { detail = { message: text }; }
  return new ServiceAPIError(response.status, detail);
}
export class ServiceClient {
  constructor(private sessionId: string, private base = '', private headers: () => HeadersInit = () => ({})) {}
  private sessionPath() { return `/api/v1/agent-sessions/${encodeURIComponent(this.sessionId)}`; }
  private path(id: string) { return `/api/v1/agent-sessions/${encodeURIComponent(this.sessionId)}/turns/${encodeURIComponent(id)}`; }
  private async request<T>(path: string, method = 'GET', body?: unknown, key?: string, signal?: AbortSignal): Promise<T> {
    const headers = new Headers(this.headers());
    if (body !== undefined) headers.set('Content-Type', 'application/json');
    if (key) headers.set('Idempotency-Key', key);
    const response = await fetch(this.base + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body), signal });
    if (!response.ok) throw await serviceError(response);
    return response.status === 204 ? undefined as T : await response.json() as T;
  }
  submit(input: unknown, key: string) {
    return this.request<ServiceSubmission>(`/api/v1/agent-sessions/${encodeURIComponent(this.sessionId)}/turns`, 'POST', input, key);
  }
  async turnCapabilities(id: string) {
    const value = await this.request<{ available_commands: string[]; supported_commands: Record<string, boolean> }>(this.path(id) + '/capabilities');
    if (!Array.isArray(value.available_commands) || !value.supported_commands) throw new ServiceProtocolError('Unsupported Turn capabilities schema; use matching Service and client versions.');
    return { ...value, available_commands: value.available_commands.map(kind => kind === 'steer' ? 'inputs' : kind) };
  }
  async commandStatus(id: string, commandId: string) {
    const value = await this.request<ServiceData>(this.sessionPath() + '/commands/' + encodeURIComponent(commandId));
    if (value.turn_id !== id) throw new Error('Command does not belong to this Turn');
    return { command: commandReceipt(value) };
  }
  actions(id: string) { return this.resources(id, 'required_actions'); }
  artifacts(id: string) { return this.resources(id, 'artifacts'); }
  async usage(id: string) { return (await this.request<{ data: ServiceData }>(this.path(id) + '/usage')).data; }
  webhooks() { return this.request<ServiceData>(this.sessionPath() + '/webhooks'); }
  webhook(_id: string, url: string, key: string, eventTypes: string[] = []) { return this.request<ServiceData>(this.sessionPath() + '/webhooks', 'POST', { url, event_types: eventTypes }, key); }
  deleteWebhook(_id: string, webhookId: string) { return this.request<ServiceData>(this.sessionPath() + '/webhooks/' + encodeURIComponent(webhookId), 'DELETE'); }
  retryWebhook(_id: string, webhookId: string) { return this.request<ServiceData>(this.sessionPath() + '/webhooks/' + encodeURIComponent(webhookId) + '/retry', 'POST', {}); }
  async artifact(id: string, artifactId: string) {
    const response = await fetch(this.base + this.path(id) + '/artifacts/' + encodeURIComponent(artifactId) + '/content', { headers: this.headers() });
    if (!response.ok) throw await serviceError(response);
    if (Number(response.headers.get('Content-Length')) > 100 * 1024 * 1024) { await response.body?.cancel(); throw new Error('Artifact exceeds the 100 MiB download limit'); }
    const reader = response.body?.getReader();
    if (!reader) throw new Error('Artifact response has no content');
    let size = 0;
    const chunks: Uint8Array<ArrayBuffer>[] = [];
    try {
      while (true) {
        const chunk = await reader.read(); if (chunk.done) break;
        size += chunk.value.byteLength;
        if (size > 100 * 1024 * 1024) throw new Error('Artifact exceeds the 100 MiB download limit');
        chunks.push(new Uint8Array(chunk.value));
      }
      return new Blob(chunks, { type: response.headers.get('Content-Type') ?? 'application/octet-stream' });
    } finally { await reader.cancel().catch(() => undefined); reader.releaseLock(); }
  }
  async snapshot(id: string, signal?: AbortSignal, limit = 100) {
    const value = await this.request<{ snapshot_id: string; as_of: string; resources: Record<string, AgentResourcePage>; usage: ServiceData }>(this.path(id) + '/snapshot?limit=' + limit, 'GET', undefined, undefined, signal);
    if (!value.snapshot_id || !value.resources || typeof value.as_of !== 'string') throw new ServiceProtocolError('Unsupported Turn snapshot schema; use matching Service and client versions.');
    const rows = (kind: string) => value.resources[kind]?.data ?? [];
    const turn = rows('turns').find(event => event.data.turn_id === id)?.data;
    if (!turn) throw new Error('Turn is missing from its resource snapshot');
    return { as_of: value.as_of, turn, ...resourceIndexes(value.resources), resource_pages: value.resources, usage_summary: value.usage };
  }
  resources(id: string, kind: string, page?: AgentResourcePage) {
    const query = new URLSearchParams({ limit: '100', ...(page ? { snapshot_id: page.snapshot_id, after: page.next_cursor ?? '' } : {}) });
    return this.request<AgentResourcePage>(this.path(id) + '/resources/' + encodeURIComponent(kind) + '?' + query);
  }
  turn(id: string) { return this.request<ServiceData>(this.path(id)); }
  events(id: string, after = '', limit = 100) {
    return this.request<{ data: ServiceEvent[]; next_cursor: string; has_more: boolean }>(this.path(id) + '/events?' + new URLSearchParams({ after, limit: String(limit) }));
  }
  async command(id: string, kind: 'actions' | 'inputs' | 'cancel' | 'resume', payload: ServiceData, key: string) {
    const answer = { ...obj(payload.payload), request_id: payload.request_id,
      ...(payload.decision === 'approved' ? { allow: true } : payload.decision === 'rejected' ? { allow: false } : {}) };
    const receipt = await this.request<ServiceData>(this.path(id) + '/' + (kind === 'inputs' ? 'steer' : kind), 'POST', kind === 'actions' ? { answers: [answer] } : payload, key);
    const command = commandReceipt(receipt);
    return { command, status_url: this.sessionPath() + '/commands/' + encodeURIComponent(String(command.id)) };
  }
  async *stream(id: string, after = '', signal?: AbortSignal): AsyncGenerator<ServiceEvent> {
    const headers = new Headers(this.headers()); headers.set('Accept', 'text/event-stream');
    if (after) headers.set('Last-Event-ID', after);
    const response = await fetch(this.base + this.path(id) + '/events/stream', { headers, signal });
    if (!response.ok || !response.body) throw await serviceError(response);
    const reader = response.body.getReader(), decoder = new TextDecoder();
    let pending = '', data: string[] = [], eventSize = 0;
    try {
      while (true) {
        const chunk = await reader.read(); if (chunk.done) break;
        pending += decoder.decode(chunk.value, { stream: true });
        if (pending.length > 1024 * 1024) throw new ServiceProtocolError('Service event exceeds the 1 MiB client limit');
        let newline: number;
        while ((newline = pending.indexOf('\n')) >= 0) {
          const line = pending.slice(0, newline).replace(/\r$/, ''); pending = pending.slice(newline + 1);
          if (!line) {
            if (data.length) {
              const event = JSON.parse(data.join('\n')) as ServiceEvent;
              if (event.schema_version !== 1 || typeof event.id !== 'string' || typeof event.cursor !== 'string' || !event.data)
                throw new ServiceProtocolError('Unsupported Service event schema; use matching Service and client versions.');
              yield event;
            }
            data = []; eventSize = 0;
          } else if (line.startsWith('data:')) {
            const value = line.slice(5).replace(/^ /, ''); eventSize += value.length;
            if (eventSize > 1024 * 1024) throw new ServiceProtocolError('Service event exceeds the 1 MiB client limit');
            data.push(value);
          }
        }
      }
    } finally { await reader.cancel().catch(() => undefined); reader.releaseLock(); }
  }
}
function commandReceipt(value: ServiceData): ServiceData {
  const id = value.command_id ?? value.input_id;
  if (typeof id !== 'string' || !id) throw new ServiceProtocolError('Unsupported command receipt schema; use matching Service and client versions.');
  return { ...value, id };
}
const resourceFields: Record<string, string> = { items: 'item_id', tools: 'tool_call_id', required_actions: 'request_id', steps: 'step_id', artifacts: 'artifact_id', model_usage: 'model_call_id' };
function resourceIndexes(pages: Record<string, AgentResourcePage>) {
  const result: Record<string, Record<string, ServiceData>> = {};
  for (const [kind, field] of Object.entries(resourceFields)) {
    const index: Record<string, ServiceData> = {};
    for (const event of pages[kind]?.data ?? []) {
      const data = event.data;
      const key = kind === 'tools' ? `${execution(data)}:${data[field]}` : String(data[field] ?? data.attempt_id);
      index[key] = structuredClone(data);
    }
    result[kind === 'model_usage' ? 'usage' : kind] = index;
  }
  return result as Pick<ServiceSnapshot, 'items' | 'tools' | 'required_actions' | 'steps' | 'artifacts' | 'usage'>;
}
const execution = (data: ServiceData) => data.execution_id ?? data.run_id ?? data.turn_id;
const obj = (v: unknown): ServiceData => v && typeof v === 'object' && !Array.isArray(v) ? v as ServiceData : {};
const blocks = (v: unknown): ServiceData[] => Array.isArray(v) ? v.map(obj) : [];
const ended = (v: unknown) => ['completed', 'success', 'error', 'denied', 'interrupted', 'failed'].includes(String(v));
export class ServiceViewLimitError extends Error {
  constructor() { super('Display limit reached. Refresh to continue from a new snapshot; execution continues on the server.'); }
}
/** Initialize with snapshot, then apply only events after snapshot.as_of. */
export class ServiceView {
  private state: ServiceSnapshot;
  private seen = new Set<string>();
  private liveEvents: ServiceEvent[] = [];
  private characters = 0;
  constructor(snapshot: ServiceSnapshot) { this.reserve(snapshot); this.state = structuredClone(snapshot); this.checkRecords(); }
  private reserve(value: unknown) {
    const size = JSON.stringify(value).length;
    if (this.characters + size > 16 * 1024 * 1024) throw new ServiceViewLimitError();
    this.characters += size;
  }
  private checkRecords() {
    if (['items', 'tools', 'required_actions', 'steps', 'artifacts', 'usage'].reduce((sum, kind) =>
      sum + Object.keys(obj(this.state[kind as keyof ServiceSnapshot])).length, 0) > 8192) throw new ServiceViewLimitError();
  }
  mergeResourcePage(kind: string, page: AgentResourcePage) {
    const reference = this.state.resource_pages?.[kind];
    if (!reference || page.snapshot_id !== reference.snapshot_id || page.as_of !== reference.as_of || !resourceFields[kind])
      throw new Error('Resource snapshot changed; refresh before loading another page.');
    this.reserve(page);
    const baseline = new ServiceView({ as_of: reference.as_of, turn: {}, ...resourceIndexes({ [kind]: page }) });
    for (const event of this.liveEvents) baseline.apply(event);
    const field = kind === 'model_usage' ? 'usage' : kind;
    const current = this.state[field as keyof ServiceSnapshot] as Record<string, ServiceData>;
    const reconciled = baseline.snapshot()[field as keyof ServiceSnapshot] as Record<string, ServiceData>;
    for (const event of page.data) {
      const key = kind === 'tools' ? `${execution(event.data)}:${event.data[resourceFields[kind]]}` : String(event.data[resourceFields[kind]] ?? event.data.attempt_id);
      if (reconciled[key]) current[key] = reconciled[key]; else delete current[key];
    }
    this.state.resource_pages = { ...this.state.resource_pages, [kind]: structuredClone(page) };
    this.checkRecords();
  }
  private tool(e: ServiceEvent, block: ServiceData, status: string) {
    const id = block.id ?? block.tool_call_id; if (!id) return;
    const key = `${execution(e.data)}:${id}`, old = this.state.tools[key] ?? {};
    const next: ServiceData = { ...old, execution_id: execution(e.data), tool_call_id: id, turn_id: e.turn_id ?? e.data.turn_id ?? old.turn_id };
    if (e.type.startsWith('tool.operation.') || block.task_id != null && ['tool.delta', 'tool.progress', 'tool.output_gap'].includes(e.type)) {
      const operation = block.task_id;
      if (typeof operation !== 'string' || !operation) return;
      const metadata = { ...block }; delete metadata.output;
      next.operations = { ...obj(old.operations), [operation]: { ...obj(obj(old.operations)[operation]), ...metadata } };
      if (e.type === 'tool.delta') Object.assign(next, toolProgress(blocks(old.progress), blocks(block.output), old.progress_truncated === true));
      next.status ??= 'running';
      this.state.tools[key] = next;
      return;
    }
    for (const field of ['item_id', 'agent_id', 'model_call_id']) if (e.data[field] !== undefined) next[field] = e.data[field];
    if (block.name && !String(block.name).startsWith('__')) next.name = block.name;
    if (block.input !== undefined) next.input = block.input;
    if (typeof block.content === 'string') next.arguments = (e.type === 'item.delta' ? String(old.arguments ?? '') : '') + block.content;
    if (block.output !== undefined) { if (e.type === 'tool.delta') Object.assign(next, toolProgress(blocks(old.progress), blocks(block.output), old.progress_truncated === true)); else next.output = block.output; }
    if (block.result !== undefined) next.result = block.result;
    const value = String(block.state ?? status).toLowerCase();
    if (!ended(old.status) || !['requested', 'generating_arguments'].includes(value)) next.status = value;
    this.state.tools[key] = next;
  }
  apply(event: ServiceEvent) {
    if (this.seen.has(event.id)) return;
    if (this.liveEvents.length >= 4096) throw new ServiceViewLimitError();
    this.reserve(event);
    const e = structuredClone(event), d = e.data, s = this.state, id = String(d.item_id ?? '');
    if (e.type.startsWith('turn.')) {
      s.turn = { ...s.turn, ...d };
      if (e.type === 'turn.waiting_tool' && Array.isArray(d.pending_tool_call_ids)) {
        const pending = new Set(d.pending_tool_call_ids.map(String));
        for (const tool of Object.values(s.tools)) if (tool.turn_id === d.turn_id && pending.has(String(tool.tool_call_id)) && !ended(tool.status)) tool.status = 'waiting_tool';
      }
    }
    else if (e.type === 'item.started' || e.type === 'item.completed' || e.type === 'item.finalized') {
      s.items[id] = d; if (e.type === 'item.completed' || e.type === 'item.finalized') obj(d.item).status ??= 'completed';
      for (const b of blocks(obj(d.item).content)) if (['tool_use', 'tool_result'].includes(String(b.type))) this.tool(e, b, b.type === 'tool_use' ? 'requested' : 'completed');
    } else if (e.type === 'item.delta') {
      const old = s.items[id] ?? {}, item = obj(old.item), content = blocks(item.content);
      if (!['completed', 'incomplete'].includes(String(item.status))) {
        let active = old.active_tool_call_id;
        for (const b of blocks(d.content)) {
          if (b.type === 'text') { const tail = content[content.length - 1]; if (tail?.type === 'text') tail.text = String(tail.text ?? '') + String(b.text ?? ''); else content.push(b); }
          else if (b.type === 'tool_use') { active = b.id ?? active; this.tool(e, { ...b, id: active }, 'generating_arguments'); }
          else content.push(b);
        }
        const next: ServiceData = { ...old, ...d, item: { ...item, id, role: 'assistant', type: 'message', content, status: 'in_progress' }, active_tool_call_id: active };
        delete next.content; s.items[id] = next;
      }
    } else if (e.type.startsWith('tool.')) this.tool(e, { ...d, ...obj(d.result) }, e.type === 'tool.requested' ? 'requested' : e.type === 'tool.waiting' ? 'waiting' : e.type === 'tool.completed' ? String(d.status ?? 'completed') : 'running');
    else if (e.type === 'execution.ended' || e.type === 'run.ended') {
      for (const v of Object.values(s.items)) if (execution(v) === execution(d) && obj(v.item).status === 'in_progress') obj(v.item).status = 'incomplete';
      for (const v of Object.values(s.tools)) if (execution(v) === execution(d) && !ended(v.status)
        && !(d.status === 'suspended' && v.status === 'waiting')) v.status = 'unknown';
    } else if (e.type === 'required_action.created') s.required_actions[String(d.request_id)] = d;
    else if (e.type === 'required_action.resolved') delete s.required_actions[String(d.request_id)];
    else if (['step.updated', 'step.failed'].includes(e.type)) s.steps[String(d.step_id)] = d;
    else if (e.type === 'artifact.published') s.artifacts[String(d.artifact_id)] = d;
    else if (e.type === 'artifact.deleted') delete s.artifacts[String(d.artifact_id)];
    else if (['usage.recorded', 'model.completed'].includes(e.type)) s.usage[String(d.model_call_id ?? d.attempt_id ?? `${execution(d)}:total`)] = d;
    if (d.turn_usage && typeof d.turn_usage === 'object') s.usage_summary = obj(d.turn_usage);
    s.as_of = e.cursor; this.seen.add(e.id);
    this.liveEvents.push(structuredClone(event));
    this.checkRecords();
  }
  snapshot() { return structuredClone(this.state); }
}
