// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { readSse, SessionClient, sessionIdFrom, streamSession } from './client';
import { AgentSessionView } from './sessionView';
import type { AgentSessionEvent, AgentSessionSnapshot } from './types';

const event = (id: string, type = 'item.delta', data: Record<string, unknown> = {}): AgentSessionEvent => ({ schema_version: 1, session_id: 's', id, cursor: id, type,
  data: { turn_id: 't', item_id: 'm', content: [{ type: 'text', text: '你好' }], ...data } });
const frame = (e: AgentSessionEvent) => `id: ${e.cursor}\r\nevent: ${e.type}\r\ndata: ${JSON.stringify(e)}\r\n\r\n`;
const body = (text: string) => new ReadableStream<Uint8Array>({ start(controller) { controller.enqueue(new TextEncoder().encode(text)); controller.close(); } });
const response = (text: string) => new Response(body(text), { headers: { 'Content-Type': 'text/event-stream' } });
const empty: AgentSessionSnapshot = { session: { id: 's', agentId: 'a', environmentId: 'env', status: 'active', createdAt: 1, updatedAt: 1 }, as_of: '0', items: [], turns: [], runs: [], required_actions: [] };
afterEach(() => vi.unstubAllGlobals());

describe('standalone Session client', () => {
  it('accepts IDs and matching Session roots, rejects another gateway or API scope', () => {
    expect(sessionIdFrom(' s ', 'http://localhost:18081')).toBe('s');
    expect(sessionIdFrom('http://localhost:18081/api/v1/agent-sessions/s', 'http://localhost:18081')).toBe('s');
    expect(sessionIdFrom('http://localhost:18081/api/v1/agent-sessions/s', 'http://127.0.0.1:18081')).toBe('s');
    expect(() => sessionIdFrom('http://other/api/v1/agent-sessions/s', 'http://localhost:18081')).toThrow('不一致');
    expect(() => sessionIdFrom('/api/v1/agent-sessions/s/snapshot', 'http://localhost:18081')).toThrow('根地址');
  });
  it('decodes one-byte UTF-8 chunks, multiline data, CRLF and heartbeats; discards an unfinished tail', async () => {
    const bytes = new TextEncoder().encode(`: keepalive\r\n\r\nid: c\r\ndata: {"text":\r\ndata: "你好"}\r\n\r\ndata: {"truncated":true}`);
    const source = new ReadableStream<Uint8Array>({ start(controller) { for (const byte of bytes) controller.enqueue(new Uint8Array([byte])); controller.close(); } });
    const frames: unknown[] = [], heartbeat = vi.fn();
    await readSse(source, new AbortController().signal, (data, id) => { frames.push([JSON.parse(data), id]); }, heartbeat);
    expect(frames).toEqual([[{ text: '你好' }, 'c']]); expect(heartbeat).toHaveBeenCalledOnce();
  });
  it('reconnects from applied cursor, replays a truncated delta once, and finalizes the same message', async () => {
    const controller = new AbortController(), view = new AgentSessionView(empty);
    const delta = event('1'), final = event('2', 'item.finalized', { final_output: true, item: { status: 'completed', content: [{ type: 'text', text: '你好，完成' }] } });
    const fetcher = vi.fn().mockResolvedValueOnce(response(frame(delta) + frame(final).slice(0, -4))).mockResolvedValueOnce(response(frame(delta) + frame(final)));
    vi.stubGlobal('fetch', fetcher);
    await streamSession('/stream', 's', {}, '0', controller.signal, { onHeartbeat: vi.fn(), onState: vi.fn(), onEvent: e => { view.apply(e); if (e.id === '2') controller.abort(); } });
    expect(fetcher.mock.calls[1][0]).toBe('/stream?after=1');
    expect(fetcher.mock.calls[1][1].headers['Last-Event-ID']).toBe('1');
    expect(view.snapshot().items).toHaveLength(1); expect(view.snapshot().items[0].data.item).toMatchObject({ content: [{ type: 'text', text: '你好，完成' }] });
    expect(view.snapshot().as_of).toBe('2');
  });
  it('does not let preview events move the resume cursor', async () => {
    const controller = new AbortController(), preview = { ...event('preview'), ephemeral: true };
    const fetcher = vi.fn().mockResolvedValueOnce(response(frame(preview))).mockResolvedValueOnce(response(frame(event('1')))); vi.stubGlobal('fetch', fetcher);
    await streamSession('/stream', 's', {}, '0', controller.signal, { onHeartbeat: vi.fn(), onState: vi.fn(), onEvent: e => { if (!e.ephemeral) controller.abort(); } });
    expect(fetcher.mock.calls[1][0]).toBe('/stream?after=0');
  });
  it('stops on a wrong session or a failed reducer instead of consuming its cursor', async () => {
    const fetcher = vi.fn().mockResolvedValue(response(frame({ ...event('1'), session_id: 'other' }))); vi.stubGlobal('fetch', fetcher);
    const hooks = { onHeartbeat: vi.fn(), onState: vi.fn(), onEvent: vi.fn() };
    await expect(streamSession('/stream', 's', {}, '0', new AbortController().signal, hooks)).rejects.toThrow('Session');
    expect(hooks.onEvent).not.toHaveBeenCalled(); expect(fetcher).toHaveBeenCalledOnce();
    fetcher.mockResolvedValue(response(frame(event('1'))));
    await expect(streamSession('/stream', 's', {}, '0', new AbortController().signal, { ...hooks, onEvent: () => { throw new Error('display limit'); } })).rejects.toThrow('display limit');
    expect(fetcher).toHaveBeenCalledTimes(2);
  });
  it('aborts an idle reader without waiting for another event', async () => {
    const controller = new AbortController(), cancelled = vi.fn();
    const reading = readSse(new ReadableStream({ cancel: cancelled }), controller.signal, vi.fn(), vi.fn());
    controller.abort(); await reading; expect(cancelled).toHaveBeenCalledOnce();
  });
  it('uses snapshot resources but treats model_usage as usage records, not event envelopes', async () => {
    const page = { snapshot_id: 'fixed', as_of: '1', data: [event('1')], has_more: false, next_cursor: null, expires_at: 1000 };
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(Response.json(empty.session)).mockResolvedValueOnce(Response.json({ snapshot_id: 'fixed', as_of: '1', resources: { items: page, model_usage: { ...page, data: [{ model_call_id: 'call', usage: {} }] } }, usage: { model_calls: 10 } })));
    const snapshot = await new SessionClient('s').snapshot(new AbortController().signal);
    expect(snapshot.view.usage).toMatchObject({ model_calls: 10, authoritative: true }); expect(snapshot.view.items).toHaveLength(1);
  });
});
