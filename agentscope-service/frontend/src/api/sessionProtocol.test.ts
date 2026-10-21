import { afterEach, expect, it, vi } from 'vitest';
import { AgentSessionView } from './agentSessionView';
import { decodeSessionEvent, getAgentTurnOutput, streamAgentSession, type AgentSessionSnapshot, type SessionProtocolEvent } from './agentSessions';
import { normalizeManagedSession } from './managedSessions';

vi.mock('./auth', () => ({ getToken: () => null }));
afterEach(() => vi.unstubAllGlobals());
function empty(): AgentSessionSnapshot {
  return { session: normalizeManagedSession({ id: 's', agent: { id: 'a', version: 1 }, status: 'idle', created_at: '2026-10-19T00:00:00Z', updated_at: '2026-10-19T00:00:00Z', agentscope_service: { runtime_kind: 'managed', lifecycle: 'active' } } as never), as_of: '', items: [], turns: [], runs: [], required_actions: [] };
}
it('updates a pending logical input without duplicating its message', () => {
  const reducer = new AgentSessionView(empty());
  const pending: SessionProtocolEvent = { id: 'input', type: 'user.message', content: [{ type: 'text', text: 'hello' }], processed_at: null, agentscope_service: { session_id: 's', turn_id: 't' } };
  reducer.apply(decodeSessionEvent('s', pending));
  expect((reducer.snapshot().items[0].data.item as { status: string }).status).toBe('accepted');
  reducer.apply(decodeSessionEvent('s', { ...pending, processed_at: '2026-10-19T00:00:01Z' }));
  expect(reducer.snapshot().items).toHaveLength(1);
  expect((reducer.snapshot().items[0].data.item as { status: string }).status).toBe('completed');
});
it('reconciles MCP calls/results and keeps finalization from overwriting the answer', () => {
  const reducer = new AgentSessionView(empty());
  const ext = { session_id: 's', turn_id: 't', tool_call_id: 'call' };
  const events: SessionProtocolEvent[] = [
    { id: 'use', type: 'agent.mcp_tool_use', processed_at: '2026-10-19T00:00:00Z', name: 'wait', input: { seconds: 90 }, mcp_server_name: 'long', agentscope_service: ext },
    { id: 'result', type: 'agent.mcp_tool_result', processed_at: '2026-10-19T00:01:30Z', content: [{ type: 'text', text: 'physical result' }], agentscope_service: ext },
    { id: 'answer', type: 'agent.message', processed_at: '2026-10-19T00:01:31Z', content: [{ type: 'text', text: 'done' }], agentscope_service: { session_id: 's', turn_id: 't', item_id: 'answer-item' } },
    { id: 'final', type: 'agentscope_service.item.finalized', processed_at: '2026-10-19T00:01:31Z', agentscope_service: { session_id: 's', turn_id: 't', output_event_ids: ['answer'] } },
  ];
  for (const event of events) reducer.apply(decodeSessionEvent('s', event));
  expect(reducer.snapshot().items).toHaveLength(1);
  expect(reducer.snapshot().tools).toHaveLength(1);
  expect(reducer.snapshot().tools![0].data).toMatchObject({ name: 'wait', status: 'completed', input: { seconds: 90 } });
});
it('buffers the live stream until history restoration finishes', async () => {
  const controller = new AbortController(); const order: string[] = [];
  const wire: SessionProtocolEvent = { id: 'answer', type: 'agent.message', processed_at: '2026-10-19T00:00:01Z', content: [{ type: 'text', text: 'answer' }], agentscope_service: { session_id: 's' } };
  const send = vi.fn(async (url: string) => { expect(url).toBe('/v1/sessions/s/events/stream'); order.push('open'); return new Response(`: connected\n\ndata: ${JSON.stringify(wire)}\n\n`, { headers: { 'Content-Type': 'text/event-stream' } }); });
  vi.stubGlobal('fetch', send);
  await streamAgentSession('s', { signal: controller.signal, onReady: async () => { await Promise.resolve(); order.push('history'); }, onEvent: event => { expect(event.type).toBe('item.completed'); order.push('event'); controller.abort(); } });
  expect(order).toEqual(['open', 'history', 'event']);
});
it('can retrieve final output when the observer handshake is unavailable', async () => {
  const controller = new AbortController(); const calls: string[] = [];
  vi.stubGlobal('fetch', vi.fn(async (url: string) => { calls.push(url); return url.endsWith('/stream') ? new Response('{}', { status: 503 }) : new Response(JSON.stringify({ output_available: true, turn_status: 'completed', data: [{ id: 'answer', type: 'agent.message', content: [{ type: 'text', text: 'result' }] }], has_more: false, next_cursor: null })); }));
  await streamAgentSession('s', { signal: controller.signal, onEvent: () => { throw new Error('No events expected'); }, onUnavailable: async () => { const result = await getAgentTurnOutput('s', 't'); expect(result.output_available).toBe(true); controller.abort(); } });
  expect(calls).toEqual(['/v1/sessions/s/events/stream', '/v1/sessions/s/turns/t/output?limit=100']);
});
