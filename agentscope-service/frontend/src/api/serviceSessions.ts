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
}
export interface ServiceSubmission {
  id: string; sessionId: string; status: string;
  statusUrl: string; snapshotUrl: string; eventsUrl: string;
}
export const terminalTurn = (status: unknown) => ['completed', 'partial_succeeded', 'failed', 'cancelled', 'timed_out'].includes(String(status));
export class ServiceAPIError extends Error {
  constructor(public status: number, public detail: ServiceData) { super(`Service API ${status}: ${JSON.stringify(detail)}`); }
}
async function serviceError(response: Response) {
  const text = await response.text();
  let detail: ServiceData;
  try { detail = JSON.parse(text) as ServiceData; } catch { detail = { message: text }; }
  return new ServiceAPIError(response.status, detail);
}
export class ServiceClient {
  constructor(private sessionId: string, private base = '', private headers: () => HeadersInit = () => ({})) {}
  private path(id: string) { return `/api/v1/agent-sessions/${encodeURIComponent(this.sessionId)}/turns/${encodeURIComponent(id)}`; }
  private async request<T>(path: string, method = 'GET', body?: unknown, key?: string): Promise<T> {
    const headers = new Headers(this.headers());
    if (body !== undefined) headers.set('Content-Type', 'application/json');
    if (key) headers.set('Idempotency-Key', key);
    const response = await fetch(this.base + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
    if (!response.ok) throw await serviceError(response);
    return response.status === 204 ? undefined as T : await response.json() as T;
  }
  submit(input: unknown, key: string) {
    return this.request<ServiceSubmission>(`/api/v1/agent-sessions/${encodeURIComponent(this.sessionId)}/turns`, 'POST', input, key);
  }
  turnCapabilities(id: string) { return this.request<{ available_commands: string[] }>(this.path(id) + "/capabilities"); }
  commandStatus(id: string, commandId: string) { return this.request<{ command: ServiceData }>(this.path(id) + '/commands/' + encodeURIComponent(commandId)); }
  actions(id: string) { return this.request<ServiceSnapshot>(this.path(id) + '/actions'); }
  artifacts(id: string) { return this.request<ServiceSnapshot>(this.path(id) + '/artifacts'); }
  usage(id: string) { return this.request<ServiceSnapshot>(this.path(id) + '/usage'); }
  webhooks(id: string) { return this.request<ServiceData>(this.path(id) + '/webhooks'); }
  webhook(id: string, url: string, key: string, eventTypes: string[] = []) { return this.request<ServiceData>(this.path(id) + '/webhooks', 'POST', { url, event_types: eventTypes }, key); }
  deleteWebhook(id: string, webhookId: string) { return this.request<ServiceData>(this.path(id) + '/webhooks/' + encodeURIComponent(webhookId), 'DELETE'); }
  retryWebhook(id: string, webhookId: string) { return this.request<ServiceData>(this.path(id) + '/webhooks/' + encodeURIComponent(webhookId) + '/retry', 'POST', {}); }
  async artifact(id: string, artifactId: string) {
    const response = await fetch(this.base + this.path(id) + '/artifacts/' + encodeURIComponent(artifactId), { headers: this.headers() });
    if (!response.ok) throw new Error(`Artifact HTTP ${response.status}: ${await response.text()}`);
    return response.blob();
  }
  snapshot(id: string) { return this.request<ServiceSnapshot>(this.path(id) + '/snapshot'); }
  turn(id: string) { return this.request<ServiceData>(this.path(id)); }
  events(id: string, after = '', limit = 100) {
    return this.request<{ data: ServiceEvent[]; next_cursor: string; has_more: boolean }>(this.path(id) + '/events?' + new URLSearchParams({ after, limit: String(limit) }));
  }
  command(id: string, kind: 'actions' | 'inputs' | 'cancel' | 'resume', payload: ServiceData, key: string) {
    return this.request<{ command: ServiceData; status_url: string }>(this.path(id) + '/' + kind, 'POST', payload, key);
  }
  async *stream(id: string, after = '', signal?: AbortSignal): AsyncGenerator<ServiceEvent> {
    const headers = new Headers(this.headers()); headers.set('Accept', 'text/event-stream');
    if (after) headers.set('Last-Event-ID', after);
    const response = await fetch(this.base + this.path(id) + '/events/stream', { headers, signal });
    if (!response.ok || !response.body) throw await serviceError(response);
    const reader = response.body.getReader(), decoder = new TextDecoder();
    let pending = '', data: string[] = [];
    try {
      while (true) {
        const chunk = await reader.read(); if (chunk.done) break;
        pending += decoder.decode(chunk.value, { stream: true });
        let newline: number;
        while ((newline = pending.indexOf('\n')) >= 0) {
          const line = pending.slice(0, newline).replace(/\r$/, ''); pending = pending.slice(newline + 1);
          if (!line) { if (data.length) yield JSON.parse(data.join('\n')) as ServiceEvent; data = []; }
          else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
        }
      }
    } finally { await reader.cancel().catch(() => undefined); reader.releaseLock(); }
  }
}
const obj = (v: unknown): ServiceData => v && typeof v === 'object' && !Array.isArray(v) ? v as ServiceData : {};
const blocks = (v: unknown): ServiceData[] => Array.isArray(v) ? v.map(obj) : [];
const ended = (v: unknown) => ['completed', 'success', 'error', 'denied', 'interrupted', 'failed'].includes(String(v));
/** Initialize with snapshot, then apply only events after snapshot.as_of. */
export class ServiceView {
  private state: ServiceSnapshot;
  private seen = new Set<string>();
  constructor(snapshot: ServiceSnapshot) { this.state = structuredClone(snapshot); }
  private tool(e: ServiceEvent, block: ServiceData, status: string) {
    const id = block.id ?? block.tool_call_id; if (!id) return;
    const key = `${e.data.execution_id}:${id}`, old = this.state.tools[key] ?? {};
    const next: ServiceData = { ...old, execution_id: e.data.execution_id, tool_call_id: id };
    for (const field of ['item_id', 'agent_id', 'model_call_id']) if (e.data[field] !== undefined) next[field] = e.data[field];
    if (block.name && !String(block.name).startsWith('__')) next.name = block.name;
    if (block.input !== undefined) next.input = block.input;
    if (typeof block.content === 'string') next.arguments = (e.type === 'item.delta' ? String(old.arguments ?? '') : '') + block.content;
    if (block.output !== undefined) { if (e.type === 'tool.delta') next.progress = [...blocks(old.progress), ...blocks(block.output)]; else next.output = block.output; }
    if (block.result !== undefined) next.result = block.result;
    const value = String(block.state ?? status).toLowerCase();
    if (!ended(old.status) || !['requested', 'generating_arguments'].includes(value)) next.status = value;
    this.state.tools[key] = next;
  }
  apply(event: ServiceEvent) {
    if (this.seen.has(event.id)) return;
    const e = structuredClone(event), d = e.data, s = this.state, id = String(d.item_id ?? '');
    if (e.type.startsWith('turn.')) s.turn = { ...s.turn, ...d };
    else if (e.type === 'item.started' || e.type === 'item.completed') {
      s.items[id] = d; if (e.type === 'item.completed') obj(d.item).status = 'completed';
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
    } else if (e.type.startsWith('tool.')) this.tool(e, { ...d, ...obj(d.result) }, e.type === 'tool.requested' ? 'requested' : e.type === 'tool.completed' ? String(d.status ?? 'completed') : 'running');
    else if (e.type === 'execution.ended') {
      for (const v of Object.values(s.items)) if (v.execution_id === d.execution_id && obj(v.item).status === 'in_progress') obj(v.item).status = 'incomplete';
      for (const v of Object.values(s.tools)) if (v.execution_id === d.execution_id && !ended(v.status)) v.status = 'unknown';
    } else if (e.type === 'required_action.created') s.required_actions[String(d.request_id)] = d;
    else if (e.type === 'required_action.resolved') delete s.required_actions[String(d.request_id)];
    else if (['step.updated', 'step.failed'].includes(e.type)) s.steps[String(d.step_id)] = d;
    else if (e.type === 'artifact.published') s.artifacts[String(d.artifact_id)] = d;
    else if (e.type === 'artifact.deleted') delete s.artifacts[String(d.artifact_id)];
    else if (['usage.recorded', 'model.completed'].includes(e.type)) s.usage[`${d.execution_id}:${d.model_call_id ?? 'total'}`] = d;
    s.as_of = e.cursor; this.seen.add(e.id);
    if (this.seen.size > 4096) this.seen.delete(this.seen.values().next().value!);
  }
  snapshot() { return structuredClone(this.state); }
}
