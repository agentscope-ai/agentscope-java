import { afterEach, expect, it, vi } from 'vitest';
import { ServiceClient, ServiceView, terminalTurn, type ServiceEvent, type ServiceSnapshot } from './serviceSessions';

it('keeps operation outcomes separate from the model tool state across refresh', () => {
  const view = new ServiceView({ as_of: '', turn: {}, items: {}, tools: {}, required_actions: {}, steps: {}, artifacts: {}, usage: {} });
  const event = (id: string, type: string, data: ServiceEvent['data']): ServiceEvent => ({ schema_version: 1, id, cursor: id, turn_id: 'turn', created_at: 1, type, data: { execution_id: 'run', turn_id: 'turn', ...data } });
  view.apply(event('1', 'tool.requested', { tool_call_id: 'call', name: 'execute' }));
  view.apply(event('2', 'turn.waiting_tool', { pending_tool_call_ids: ['call'] }));
  view.apply(event('3', 'tool.operation.completed', { tool_call_id: 'call', task_id: 'task', name: 'sandbox.exec', operation_execution_id: 'remote', status: 'succeeded' }));
  expect(Object.keys(view.snapshot().tools)).toEqual(['run:call']);
  expect(view.snapshot().tools['run:call']).toMatchObject({ name: 'execute', status: 'waiting_tool', operations: { task: { status: 'succeeded', operation_execution_id: 'remote' } } });
  const restored = new ServiceView(view.snapshot());
  restored.apply(event('4', 'tool.completed', { tool_call_id: 'call', status: 'completed', output: 'final result' }));
  restored.apply(event('5', 'tool.operation.completed', { tool_call_id: 'call', task_id: 'task', name: 'sandbox.exec', status: 'succeeded' }));
  expect(Object.keys(restored.snapshot().tools)).toEqual(['run:call']);
  expect(restored.snapshot().tools['run:call']).toMatchObject({ name: 'execute', status: 'completed', output: 'final result' });
});

it('restores a message prefix and applies tool results from multiple executions without duplication', () => {
  const snapshot: ServiceSnapshot = {
    as_of: 'snapshot-cursor', turn: { status: 'running' },
    items: { message: { execution_id: 'a', item: { content: [{ type: 'text', text: 'Before ' }], status: 'in_progress' } } },
    tools: {}, required_actions: {}, steps: {}, artifacts: {}, usage: {},
  };
  const view = new ServiceView(snapshot);
  const event = (id: string, type: string, data: ServiceEvent['data']): ServiceEvent => ({
    schema_version: 1, id, type, turn_id: 'turn', created_at: 1, cursor: id, data,
  });
  const delta = event('1', 'item.delta', { execution_id: 'a', item_id: 'message', content: [{ type: 'text', text: 'and after' }] });
  view.apply(delta);
  view.apply(delta);
  view.apply(event('2', 'tool.requested', { execution_id: 'a', tool_call_id: 'lookup', name: 'search' }));
  view.apply(event('3', 'tool.completed', { execution_id: 'b', tool_call_id: 'lookup', output: 'other agent result' }));
  view.apply(event('4', 'tool.completed', { execution_id: 'a', tool_call_id: 'lookup', output: 'first agent result' }));
  view.apply(event('5', 'execution.ended', { execution_id: 'a' }));
  view.apply(event('6', 'step.failed', { step_id: 'review', status: 'failed', message: 'Member failed' }));
  const restored = view.snapshot();
  expect(restored.items.message.item).toMatchObject({ content: [{ type: 'text', text: 'Before and after' }], status: 'incomplete' });
  expect(restored.tools['a:lookup']).toMatchObject({ status: 'completed', output: 'first agent result' });
  expect(restored.tools['b:lookup']).toMatchObject({ status: 'completed', output: 'other agent result' });
  expect(restored.as_of).toBe('6');
  expect(restored.steps.review).toMatchObject({ status: 'failed', message: 'Member failed' });
  expect(snapshot.items.message.item).toMatchObject({ content: [{ type: 'text', text: 'Before ' }] });
});

afterEach(() => vi.unstubAllGlobals());

it('preserves waiting tools and finalizes one existing message', () => {
  const view = new ServiceView({ as_of: '', turn: {}, items: {}, tools: {}, required_actions: {}, steps: {}, artifacts: {}, usage: {} });
  const event = (id: string, type: string, data: ServiceEvent['data']): ServiceEvent => ({ schema_version: 1, id, cursor: id, turn_id: 'turn', created_at: 1, type, data });
  view.apply(event('1', 'tool.requested', { execution_id: 'run', tool_call_id: 'call' }));
  view.apply(event('2', 'tool.waiting', { execution_id: 'run', tool_call_id: 'call', status: 'waiting' }));
  view.apply(event('3', 'run.ended', { execution_id: 'run', status: 'suspended' }));
  expect(view.snapshot().tools['run:call'].status).toBe('waiting');
  view.apply(event('4', 'item.completed', { item_id: 'message', item: { content: 'draft' } }));
  view.apply(event('5', 'item.finalized', { item_id: 'message', final_output: true, item: { status: 'completed', content: 'final' } }));
  expect(Object.keys(view.snapshot().items)).toEqual(['message']);
  expect(view.snapshot().items.message).toMatchObject({ final_output: true, item: { content: 'final' } });
});

it('marks unfinished Managed output incomplete when its native run ends', () => {
  const view = new ServiceView({ as_of: 'before', turn: { status: 'running' },
    items: { partial: { execution_id: 'run', item: { content: [{ type: 'text', text: 'partial' }], status: 'in_progress' } } },
    tools: { waiting: { execution_id: 'run', status: 'running' } }, required_actions: {}, steps: {}, artifacts: {}, usage: {} });
  view.apply({ schema_version: 1, id: 'end', cursor: 'after', type: 'run.ended', turn_id: 'turn', created_at: 1,
    data: { execution_id: 'run', status: 'interrupted' } });
  expect(view.snapshot().items.partial.item).toMatchObject({ status: 'incomplete' });
  expect(view.snapshot().tools.waiting.status).toBe('unknown');
});

it('uses the Agent Service action and steering contract for every backend', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ command_id: 'answer', turn_id: 't', status: 'queued', command_status: 'queued' })))
    .mockResolvedValueOnce(new Response(JSON.stringify({ input_id: 'input', turn_id: 't', status: 'runtime_accepted', input_status: 'applied' })));
  vi.stubGlobal('fetch', fetcher);
  const client = new ServiceClient('s', '', () => ({ 'X-API-Key': 'fixture-key' }));
  const accepted = await client.command('t', 'actions', { request_id: 'approval', decision: 'rejected', payload: { reason: 'reviewed' } }, 'answer-key');
  expect(JSON.parse(fetcher.mock.calls[0][1].body)).toEqual({ answers: [{ request_id: 'approval', allow: false, reason: 'reviewed' }] });
  expect(new Headers(fetcher.mock.calls[0][1].headers).get('Idempotency-Key')).toBe('answer-key');
  expect(accepted.command).toMatchObject({ id: 'answer', status: 'queued', command_status: 'queued' });
  expect(accepted.status_url).toBe('/api/v1/agent-sessions/s/commands/answer');
  const applied = await client.command('t', 'inputs', { message: 'correction' }, 'steer-key');
  expect(fetcher.mock.calls[1][0]).toBe('/api/v1/agent-sessions/s/turns/t/steer');
  expect(applied.command).toMatchObject({ id: 'input', status: 'runtime_accepted', input_status: 'applied' });
});

it('reads command status from the Session and rejects another Turn', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ command_id: 'c', turn_id: 't', command_status: 'applied' })))
    .mockResolvedValueOnce(new Response(JSON.stringify({ command_id: 'c', turn_id: 'other', command_status: 'applied' })));
  vi.stubGlobal('fetch', fetcher);
  const client = new ServiceClient('s');
  expect((await client.commandStatus('t', 'c')).command.command_status).toBe('applied');
  expect(fetcher.mock.calls[0][0]).toBe('/api/v1/agent-sessions/s/commands/c');
  await expect(client.commandStatus('t', 'c')).rejects.toThrow('does not belong');
});

it('reads Agent Service resource snapshots and downloads owned content without resolving caller URIs', async () => {
  const page = (data: unknown[]) => ({ snapshot_id: 'snapshot', as_of: 'cursor', expires_at: 100, data, next_cursor: null, has_more: false });
  const fetcher = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ snapshot_id: 'snapshot', as_of: 'cursor', resources: {
    turns: page([{ data: { turn_id: 't', status: 'completed' } }]), artifacts: page([{ data: { artifact_id: 'report', name: 'report', uri: 'https://files.example/report' } }]),
  }, usage: { model_calls: 1, totals: { inputTokens: 7 } } })))
    .mockResolvedValueOnce(new Response('report content'))
    .mockResolvedValueOnce(new Response(JSON.stringify({ turn: {} })));
  vi.stubGlobal('fetch', fetcher);
  const client = new ServiceClient('s', '', () => ({ 'X-API-Key': 'fixture-key' }));
  const snapshot = await client.snapshot('t');
  expect(snapshot.turn.status).toBe('completed');
  expect(snapshot.usage_summary).toMatchObject({ model_calls: 1, totals: { inputTokens: 7 } });
  expect(snapshot.artifacts.report.uri).toBe('https://files.example/report');
  expect(await (await client.artifact('t', 'report')).text()).toBe('report content');
  expect(fetcher.mock.calls[1][0]).toBe('/api/v1/agent-sessions/s/turns/t/artifacts/report/content');
  expect(new Headers(fetcher.mock.calls[1][1].headers).get('X-API-Key')).toBe('fixture-key');
  await expect(client.snapshot('t')).rejects.toThrow('Unsupported Turn snapshot schema');
});

it('reconciles late pages with live message deltas and deleted artifacts', () => {
  const reference = { snapshot_id: 'snapshot', as_of: 'before', expires_at: 100, data: [], next_cursor: 'next', has_more: true };
  const view = new ServiceView({ as_of: 'before', turn: { status: 'running' }, items: {}, tools: {}, required_actions: {}, steps: {}, artifacts: {}, usage: {}, resource_pages: { items: reference, artifacts: reference } });
  const event = (id: string, type: string, data: ServiceEvent['data']): ServiceEvent => ({ schema_version: 1, id, type, turn_id: 't', cursor: id, created_at: 1, data });
  view.apply(event('delta', 'item.delta', { item_id: 'message', run_id: 'run', content: [{ type: 'text', text: 'after' }] }));
  view.apply(event('deleted', 'artifact.deleted', { artifact_id: 'report' }));
  view.mergeResourcePage('items', { ...reference, has_more: false, next_cursor: null, data: [{ type: 'item.started', session_id: 's', data: { item_id: 'message', run_id: 'run', item: { status: 'in_progress', content: [{ type: 'text', text: 'before ' }] } } }] });
  view.mergeResourcePage('artifacts', { ...reference, has_more: false, next_cursor: null, data: [{ type: 'artifact.published', session_id: 's', data: { artifact_id: 'report' } }] });
  expect(view.snapshot().items.message.item).toMatchObject({ content: [{ type: 'text', text: 'before after' }] });
  expect(view.snapshot().artifacts).toEqual({});
  expect(view.snapshot().as_of).toBe('deleted');
  expect(view.snapshot().resource_pages?.items.has_more).toBe(false);
  expect(terminalTurn('interrupted')).toBe(true);
});


it('shows a durable tool suspension without asking the user to supply its result', () => {
  const view = new ServiceView({ as_of: '', turn: {}, items: {}, tools: {}, required_actions: {}, steps: {}, artifacts: {}, usage: {} });
  const event = (id: string, type: string, data: ServiceEvent['data']): ServiceEvent => ({ schema_version: 1, id, cursor: id, turn_id: 'turn', created_at: 1, type, data });
  view.apply(event('1', 'tool.requested', { execution_id: 'run', tool_call_id: 'call' }));
  view.apply(event('2', 'run.ended', { execution_id: 'run', status: 'failed' }));
  view.apply(event('3', 'turn.waiting_tool', { turn_id: 'turn', status: 'waiting_tool', pending_tool_call_ids: ['call'] }));
  expect(view.snapshot().turn.status).toBe('waiting_tool');
  expect(view.snapshot().tools['run:call'].status).toBe('waiting_tool');
  expect(view.snapshot().required_actions).toEqual({});
  expect(terminalTurn('waiting_tool')).toBe(false);
  view.apply(event('4', 'tool.completed', { execution_id: 'run', tool_call_id: 'call', status: 'completed' }));
  expect(view.snapshot().tools['run:call'].status).toBe('completed');
});

it('bounds remote output separately from the model result and retains terminal state on later source facts', () => {
  const view = new ServiceView({ as_of: '', turn: {}, items: {}, tools: {}, required_actions: {}, steps: {}, artifacts: {}, usage: {} });
  const event = (id: string, type: string, data: ServiceEvent['data']): ServiceEvent => ({ schema_version: 1, id, cursor: id, turn_id: 'turn', created_at: 1, type, data: { execution_id: 'run', ...data } });
  view.apply(event('1', 'tool.requested', { tool_call_id: 'call', name: 'execute' }));
  view.apply(event('2', 'tool.waiting', { tool_call_id: 'call', status: 'waiting' }));
  for (let i = 0; i < 8; i++) view.apply(event(String(i + 3), 'tool.delta', { tool_call_id: 'call', task_id: 'operation', operation_name: 'sandbox.exec', source_seq: i + 1, output: [{ type: 'text', text: 'x'.repeat(16384) }] }));
  const state = view.snapshot();
  expect(Object.keys(state.tools)).toEqual(['run:call']);
  expect(state.tools['run:call']).toMatchObject({ name: 'execute', status: 'waiting', progress_truncated: true, progress: [{ type: 'text', text: 'x'.repeat(65536) }] });
  const restored = new ServiceView(state);
  restored.apply(event('11', 'tool.completed', { tool_call_id: 'call', status: 'success' }));
  restored.apply(event('12', 'tool.progress', { tool_call_id: 'call', task_id: 'operation', status: 'running', source_seq: 9 }));
  expect(restored.snapshot().tools['run:call'].status).toBe('success');
  expect(restored.snapshot().tools['run:call'].operations).toMatchObject({ operation: { status: 'running', source_seq: 9 } });
});
