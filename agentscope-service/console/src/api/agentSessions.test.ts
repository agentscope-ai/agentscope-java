import { afterEach, describe, expect, it, vi } from 'vitest';
import { answerAgentSignal, getAgentSessionSnapshot, getAgentSubagentSnapshot, getAgentTurnCapabilities, listAgentTurnPage, mergeAgentTurns, isAgentTurnOutcome, waitForAgentTurn, listAgentCheckpoints, restoreAgentCheckpoint, forkAgentSession, getAgentCheckpointOperation, cancelAgentCheckpointOperation, setAgentBudget, type AgentSessionEvent } from './agentSessions';

vi.mock('./auth', () => ({ getToken: () => null }));
afterEach(() => vi.unstubAllGlobals());

describe('logical turn outcomes', () => {
  const event = (type: string, turn = 't'): AgentSessionEvent => ({
    type, session_id: 's', data: { turn_id: turn, run_id: 'r', status: 'completed' },
  });
  it('does not finish a turn when one execution ends', () => {
    for (const type of ['run.started', 'run.ended', 'turn.ended', 'turn.running', 'required_action.created'])
      expect(isAgentTurnOutcome(event(type), 't')).toBe(false);
  });
  it('waits for the matching durable command outcome', () => {
    for (const type of ['turn.completed', 'turn.failed', 'turn.cancelled', 'turn.interrupted', 'turn.requires_action']) {
      expect(isAgentTurnOutcome(event(type), 't')).toBe(true);
      expect(isAgentTurnOutcome(event(type, 'other'), 't')).toBe(false);
    }
  });
});

describe('Agent Service resource API', () => {
  it('pins sparse checkpoint pages and keeps recovery receipt IDs for explicit status reads', async () => {
    const send = vi.fn(async (_url: string, _init?: RequestInit) => {
      void _url; void _init;
      return new Response(JSON.stringify({ data: [], as_of: 'fixed+/=', next_cursor: 'scan+/=', has_more: true,
        command_id: 'receipt', operation_id: 'receipt', source_session_id: 's', command_status: 'validating', session_id: 'target' }), { status: 202 });
    });
    vi.stubGlobal('fetch', send);
    const page = await listAgentCheckpoints('s');
    expect(page.data).toEqual([]);
    await listAgentCheckpoints('s', page.next_cursor ?? undefined, page.as_of);
    const query = new URL(send.mock.calls[1][0], 'http://fixture').searchParams;
    expect(query.get('through')).toBe('fixed+/=');
    expect(query.get('after')).toBe('scan+/=');
    const receipt = await restoreAgentCheckpoint('s', 'checkpoint_saved', 'same-request');
    expect(receipt.command_status).toBe('validating');
    expect(send.mock.calls[2][1]?.headers).toMatchObject({ 'Idempotency-Key': 'same-request' });
    expect(JSON.parse(String(send.mock.calls[2][1]?.body))).toEqual({ checkpoint_id: 'checkpoint_saved' });
    await forkAgentSession('s', 'target', 'checkpoint_saved', 'same-fork');
    expect(JSON.parse(String(send.mock.calls[3][1]?.body))).toMatchObject({ target_session_id: 'target' });
    await getAgentCheckpointOperation('s', receipt.operation_id);
    expect(send.mock.calls[4][0]).toBe('/v1/sessions/s/checkpoint-operations/receipt');
    await cancelAgentCheckpointOperation('s', receipt.operation_id);
    expect(send.mock.calls[5][0]).toBe('/v1/sessions/s/checkpoint-operations/receipt/cancel');
    await expect(getAgentCheckpointOperation('other', 'receipt')).rejects.toThrow('another operation');
  });
  it('saves budget with the observed revision and preserves decimal input as a string', async () => {
    const send = vi.fn(async (_url: string, _init?: RequestInit) => {
      void _url; void _init; return new Response(JSON.stringify({ limits: {}, revision: 8 }), { status: 202 });
    });
    vi.stubGlobal('fetch', send);
    await setAgentBudget('s', { max_cost: '12345678901234567890.123456789', currency: 'USD' }, 7);
    expect(send.mock.calls[0][1]?.headers).toMatchObject({ 'If-Match': '7' });
    expect(JSON.parse(String(send.mock.calls[0][1]?.body)).max_cost).toBe('12345678901234567890.123456789');
  });
  it('reads target history pages without requiring snapshots', async () => {
    const calls: string[] = [];
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      calls.push(url);
      return new Response(JSON.stringify(url.includes('/events?') ? { data: [{ id: 'message', type: 'agent.message', processed_at: '2026-10-19T00:00:00Z', content: [{ type: 'text', text: 'answer' }], managed_agents: { turn_id: 't' } }], next_page: null } : { id: 's', agent: { id: 'a', version: 1 }, status: 'idle', created_at: '2026-10-19T00:00:00Z', updated_at: '2026-10-19T00:00:00Z', managed_agents: { runtime_kind: 'managed', lifecycle: 'active' } }));
    }));
    const view = await getAgentSessionSnapshot('s');
    expect(view.items[0].data.item).toMatchObject({ role: 'assistant', content: [{ type: 'text', text: 'answer' }] });
    expect(calls.some(url => url.includes('/snapshot'))).toBe(false);
  });
  it('loads latest turns with scoped paging and reads server capabilities', async () => {
    const send = vi.fn(async (url: string) => new Response(JSON.stringify(url.endsWith('/capabilities') ? {
      runtime_kind: 'workflow', available_commands: ['cancel', 'actions'], supported_commands: { resume: false, steer: false },
    } : { data: [], has_more: false, next_cursor: null })));
    vi.stubGlobal('fetch', send);
    await listAgentTurnPage('s', 'opaque cursor');
    const query = new URL(send.mock.calls[0][0], 'http://fixture').searchParams;
    expect(query.get('order')).toBe('desc'); expect(query.get('after')).toBe('opaque cursor');
    await listAgentTurnPage('s', undefined, 'running');
    expect(send.mock.calls[1][0]).toContain('limit=1&order=desc&status=running');
    expect((await getAgentTurnCapabilities('s', 't')).available_commands).toEqual(['cancel', 'actions']);
    const running = { id: 'running', sessionId: 's', createdAt: 1, updatedAt: 3, admissionSequence: 1, status: 'running' };
    const latest = { ...running, id: 'latest', admissionSequence: 201, status: 'queued' };
    expect(mergeAgentTurns([running], [latest, { ...running, updatedAt: 2, status: 'queued' }])).toEqual([latest, running]);
    expect(mergeAgentTurns([running], [{ ...running, status: 'queued' }])).toEqual([running]);
    expect(mergeAgentTurns([running], [{ ...running, status: 'requires_action' }], true)[0].status).toBe('requires_action');
  });
  it('flattens child snapshots with the same resource schema', async () => {
    const item = { type: 'item.completed', session_id: 'child', data: { item_id: 'm' } };
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ snapshot_id: 'fixed', as_of: 'cursor',
      resources: { items: { data: [item] } }, usage: { model_calls: 1 } }))));
    expect(await getAgentSubagentSnapshot('s', 'child')).toMatchObject({ session: { id: 'child', parent_session_id: 's' },
      items: [item], usage: { authoritative: true, model_calls: 1 } });
  });
  it('rejects unsupported tool replies before sending work', async () => {
    const send = vi.fn(); vi.stubGlobal('fetch', send);
    await expect(answerAgentSignal('s', 't', 'request', '{"value":9007199254740993}', 'stable')).rejects.toThrow('unsupported_capability');
    expect(send).not.toHaveBeenCalled();
  });
  it('waits through Turn queries without requiring snapshots or SSE', async () => {
    const send = vi.fn(async (url: string) => { expect(url).toContain('/turns/target'); return new Response(JSON.stringify({ id: 'target', session_id: 's', status: 'completed', created_at: '2026-10-19T00:00:00Z' })); });
    vi.stubGlobal('fetch', send);
    expect((await waitForAgentTurn('s', 'target', new AbortController().signal)).type).toBe('turn.completed');
    expect(send.mock.calls[0][0]).toBe('/v1/sessions/s/turns/target');
  });
  it('rejects a malformed history response', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => new Response(JSON.stringify(url.includes('/events?') ? {} : { id: 's', agent: { id: 'a' }, managed_agents: { runtime_kind: 'managed', lifecycle: 'active' }, status: 'idle' }))));
    await expect(getAgentSessionSnapshot('s')).rejects.toThrow();
  });
});
