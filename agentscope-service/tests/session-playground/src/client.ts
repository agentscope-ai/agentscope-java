// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.
import type { AgentResourcePage, AgentSessionEvent, AgentSessionSnapshot, AgentTurn, ManagedSession } from './types';

export class ApiError extends Error {
  constructor(message: string, readonly status: number) { super(message); }
}

export function sessionIdFrom(value: string, gateway: string): string {
  const text = value.trim();
  if (!text) throw new Error('请填写 Session ID 或完整 Session URL。');
  if (!text.includes('/')) return text;
  const url = new URL(text, gateway);
  const target = new URL(gateway);
  const local = (hostname: string) => ['localhost', '127.0.0.1', '[::1]'].includes(hostname);
  const sameLocal = local(url.hostname) && local(target.hostname) && url.protocol === target.protocol && url.port === target.port;
  if (url.origin !== target.origin && !sameLocal) throw new Error('Session URL 的服务地址与当前 API 代理不一致，请修改 PLAYGROUND_API_TARGET 后重启。');
  const match = url.pathname.match(/^\/api\/v1\/agent-sessions\/([^/]+)\/?$/);
  if (!match || url.search || url.hash) throw new Error('请填写 Session 根地址，不要包含 /turns、/snapshot 或查询参数。');
  return decodeURIComponent(match[1]);
}

/** No Console login or global auth state. Credentials stay in this page's memory. */
export class SessionClient {
  readonly path: string;
  private headers: Record<string, string>;
  constructor(readonly session: string, token = '', tenant = '', namespace = '') {
    this.path = `/api/v1/agent-sessions/${encodeURIComponent(session)}`;
    this.headers = { 'Content-Type': 'application/json',
      ...(token.trim() ? { Authorization: `Bearer ${token.trim()}` } : {}),
      ...(tenant.trim() ? { 'X-AgentScope-Tenant': tenant.trim() } : {}),
      ...(namespace.trim() ? { 'X-AgentScope-Namespace': namespace.trim() } : {}),
    };
  }
  async request<T>(suffix: string, signal: AbortSignal, init: RequestInit = {}): Promise<T> {
    const response = await fetch(`${this.path}${suffix}`, { ...init, signal, headers: { ...this.headers, ...init.headers } });
    if (!response.ok) {
      const body = await response.text();
      let message = body;
      try { const parsed = JSON.parse(body); message = parsed.message ?? parsed.error?.message ?? parsed.error ?? body; } catch { /* Plain-text errors are useful too. */ }
      throw new ApiError(`HTTP ${response.status}: ${typeof message === 'string' ? message.slice(0, 1200) : JSON.stringify(message)}`, response.status);
    }
    return response.status === 204 ? undefined as T : response.json();
  }
  async snapshot(signal: AbortSignal): Promise<{ raw: unknown; view: AgentSessionSnapshot }> {
    const [session, snapshot] = await Promise.all([
      this.request<ManagedSession>('', signal),
      this.request<{ snapshot_id: string; as_of: string; usage: Record<string, unknown>; resources: Record<string, AgentResourcePage> }>('/snapshot?limit=200', signal),
    ]);
    if (session.id !== this.session || !snapshot.snapshot_id || typeof snapshot.as_of !== 'string' || !snapshot.resources)
      throw new Error('Session 或 snapshot 格式不匹配。');
    for (const [kind, page] of Object.entries(snapshot.resources)) {
      if (page.snapshot_id !== snapshot.snapshot_id || page.as_of !== snapshot.as_of || !Array.isArray(page.data))
        throw new Error('Snapshot 资源水位不一致。');
      if (kind !== 'model_usage') page.data.forEach(event => validateEvent(event, this.session));
    }
    const rows = (kind: string) => snapshot.resources[kind]?.data ?? [];
    return { raw: snapshot, view: { session, as_of: snapshot.as_of, items: rows('items'), tools: rows('tools'),
      turns: rows('turns'), runs: rows('runs'), steps: rows('steps'), required_actions: rows('required_actions'),
      inputs: rows('inputs'), action_commands: rows('action_commands'), subagents: rows('subagents'), artifacts: rows('artifacts'),
      usage: { ...snapshot.usage, authoritative: true }, resource_pages: snapshot.resources } };
  }
  page(kind: string, page: AgentResourcePage, signal: AbortSignal) {
    return this.request<AgentResourcePage>(`/resources/${encodeURIComponent(kind)}?${new URLSearchParams({ snapshot_id: page.snapshot_id, after: page.next_cursor ?? '', limit: '200' })}`, signal);
  }
  submit(message: string, key: string, signal: AbortSignal) {
    return this.request<AgentTurn>('/turns', signal, { method: 'POST', headers: { 'Idempotency-Key': key }, body: JSON.stringify({ message }) });
  }
  turn(id: string, signal: AbortSignal) { return this.request<AgentTurn>(`/turns/${encodeURIComponent(id)}`, signal); }
  stream(after: string, signal: AbortSignal, hooks: StreamHooks) {
    return streamSession(`${this.path}/events/stream`, this.session, this.headers, after, signal, hooks);
  }
}

export function validateEvent(value: unknown, session: string): asserts value is AgentSessionEvent {
  const event = value as AgentSessionEvent | null;
  if (!event || event.session_id !== session || event.schema_version !== undefined && event.schema_version !== 1 ||
      typeof event.type !== 'string' || !event.data || typeof event.data !== 'object' || Array.isArray(event.data))
    throw new Error('SSE / snapshot 事件的 Session、schema 或 data 格式不匹配。');
}

/** Incremental UTF-8 + LF/CRLF parser; only a blank line commits a frame. */
export async function readSse(body: ReadableStream<Uint8Array>, signal: AbortSignal, onFrame: (data: string, id?: string) => Promise<void> | void, onHeartbeat: () => void) {
  const reader = body.getReader(), decoder = new TextDecoder();
  let buffer = '', data: string[] = [], id: string | undefined, size = 0;
  const cancel = () => { void reader.cancel().catch(() => {}); };
  signal.addEventListener('abort', cancel, { once: true });
  try {
    while (!signal.aborted) {
      const chunk = await reader.read();
      if (chunk.done) break;
      buffer += decoder.decode(chunk.value, { stream: true });
      let newline: number;
      while ((newline = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, newline).replace(/\r$/, ''); buffer = buffer.slice(newline + 1);
        size += line.length;
        if (size > 1024 * 1024) throw new Error('单个 SSE 事件超过 1 MiB，请刷新快照继续观察。');
        if (!line) {
          if (data.length && !signal.aborted) await onFrame(data.join('\n'), id);
          data = []; id = undefined; size = 0;
        } else if (line.startsWith(':')) onHeartbeat();
        else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, ''));
        else if (line.startsWith('id:')) { const next = line.slice(3).replace(/^ /, ''); if (!next.includes('\0')) id = next; }
      }
      if (buffer.length + size > 1024 * 1024) throw new Error('单个 SSE 事件超过 1 MiB，请刷新快照继续观察。');
    }
    // A truncated last frame must be replayed from the last applied cursor.
  } finally { signal.removeEventListener('abort', cancel); await reader.cancel().catch(() => {}); reader.releaseLock(); }
}

export interface StreamHooks {
  onEvent: (event: AgentSessionEvent) => Promise<void> | void;
  onState: (state: 'connected' | 'reconnecting', detail?: string) => void;
  onHeartbeat: () => void;
}
function pause(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise(resolve => {
    const done = () => { clearTimeout(timer); signal.removeEventListener('abort', done); resolve(); };
    const timer = setTimeout(done, ms); signal.addEventListener('abort', done, { once: true }); if (signal.aborted) done();
  });
}
export async function streamSession(path: string, session: string, headers: Record<string, string>, after: string, signal: AbortSignal, hooks: StreamHooks) {
  let cursor = after, delay = 500;
  while (!signal.aborted) {
    try {
      const response = await fetch(`${path}?${new URLSearchParams({ ...(cursor ? { after: cursor } : {}) })}`, {
        signal, headers: { ...headers, Accept: 'text/event-stream', ...(cursor ? { 'Last-Event-ID': cursor } : {}) },
      });
      if (!response.ok) throw new ApiError(`SSE HTTP ${response.status}: ${(await response.text()).slice(0, 600)}`, response.status);
      if (!response.body || !response.headers.get('Content-Type')?.includes('text/event-stream')) throw new Error('响应不是 SSE 流。');
      hooks.onState('connected');
      await readSse(response.body, signal, async (data, id) => {
        const event: unknown = JSON.parse(data); validateEvent(event, session);
        if (!event.ephemeral && id && event.cursor && id !== event.cursor) throw new Error('SSE id 与事件 cursor 不一致。');
        const applied = !event.ephemeral && !event.cursor && id ? { ...event, cursor: id } : event;
        await hooks.onEvent(applied);
        if (!event.ephemeral) cursor = event.cursor || id || cursor;
        delay = 500;
      }, hooks.onHeartbeat);
    } catch (error) {
      if (signal.aborted) return;
      if (error instanceof ApiError && (error.status === 429 || error.status >= 500) || error instanceof TypeError) {
        hooks.onState('reconnecting', error instanceof Error ? error.message : String(error));
      } else throw error;
    }
    if (!signal.aborted) { hooks.onState('reconnecting'); await pause(delay, signal); delay = Math.min(delay * 2, 10000); }
  }
}
