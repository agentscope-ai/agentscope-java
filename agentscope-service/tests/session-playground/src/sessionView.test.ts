// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.
import { describe, it, expect } from 'vitest';
import { AgentSessionView, AgentViewLimitError } from './sessionView';
import type { AgentSessionEvent, AgentSessionSnapshot } from './types';
const empty: AgentSessionSnapshot = {
  session: { id: 's', agentId: 'a', environmentId: 'env', status: 'running', createdAt: 1, updatedAt: 1 },
  as_of: '0', items: [], turns: [], runs: [], required_actions: [],
};
const event = (id: number, type: string, data: Record<string, unknown>): AgentSessionEvent => ({
  schema_version: 1, id: String(id), type, session_id: 's', cursor: String(id), created_at: 1,
  data: { turn_id: 't', run_id: 'r', ...data },
});
const timeline = [
  event(1, 'item.started', { item_id: 'm1', item: { id: 'm1', status: 'in_progress', content: [] } }),
  event(2, 'item.delta', { item_id: 'm1', content: [{ type: 'text', text: 'Before' }, { type: 'tool_use', id: 'call', name: 'search', state: 'PENDING', content: '{"q":' }] }),
  event(3, 'item.delta', { item_id: 'm1', content: [{ type: 'tool_use', content: '"test"}' }] }),
  event(4, 'tool.dispatched', { tool_call_id: 'call', input: { q: 'test' } }),
  event(5, 'tool.delta', { tool_call_id: 'call', output: [{ type: 'text', text: 'progress' }] }),
  event(6, 'tool.completed', { tool_call_id: 'call', result: { type: 'tool_result', id: 'call', state: 'SUCCESS', output: [{ type: 'text', text: 'result' }] } }),
  event(7, 'item.completed', { item_id: 'm1', item: { id: 'm1', status: 'completed', content: [{ type: 'text', text: 'Before' }, { type: 'tool_use', id: 'call', input: { q: 'test' } }] } }),
  event(8, 'item.completed', { item_id: 'm2', item: { id: 'm2', status: 'completed', content: [{ type: 'text', text: 'Done' }] } }),
  event(9, 'subagent.started', { childSessionId: 'child' }),
  event(10, 'artifact.published', { artifact_id: 'artifact' }),
];
describe('resumable session view', () => {
  it('keeps external tools waiting across suspension and replaces the final answer in place', () => {
    const view = new AgentSessionView(empty);
    view.apply(event(1, 'tool.requested', { run_id: 'first', tool_call_id: 'call', name: 'execute' }));
    view.apply(event(2, 'tool.waiting', { run_id: 'first', tool_call_id: 'call', status: 'waiting' }));
    view.apply(event(3, 'run.ended', { run_id: 'first', status: 'suspended' }));
    expect(view.snapshot().tools?.[0].data.status).toBe('waiting');
    view.apply(event(4, 'tool.completed', { run_id: 'next', tool_call_id: 'call', status: 'completed', result: { type: 'tool_result', id: 'call', state: 'success', output: [{ type: 'text', text: 'real result' }] } }));
    view.apply(event(5, 'item.completed', { item_id: 'answer', item: { status: 'completed', content: [{ type: 'text', text: 'draft' }] } }));
    view.apply(event(6, 'item.finalized', { item_id: 'answer', final_output: true, item: { status: 'completed', content: [{ type: 'text', text: 'final' }] } }));
    expect(view.snapshot().tools?.[0].data.status).toBe('success');
    expect(view.snapshot().items).toHaveLength(1);
    expect(view.snapshot().items[0].data).toMatchObject({ final_output: true, item: { content: [{ type: 'text', text: 'final' }] } });
  });
  it('uses complete usage summaries even when model resource history is paginated', () => {
    const view = new AgentSessionView({ ...empty, usage: { authoritative: true, model_calls: 1000, totals: { inputTokens: 5000 } } });
    expect(view.snapshot().usage?.model_calls).toBe(1000);
    view.apply(event(1, 'usage.recorded', { model_call_id: 'call', usage: { inputTokens: 3 }, session_usage: { scope: 'session_only', model_calls: 1001, totals: { inputTokens: 5003 } } }));
    const restored = new AgentSessionView(view.snapshot());
    restored.apply(event(2, 'model.completed', { model_call_id: 'call', usage: { inputTokens: 3 }, session_usage: { scope: 'session_only', model_calls: 1001, totals: { inputTokens: 5003 } } }));
    expect(restored.snapshot().usage).toMatchObject({ authoritative: true, model_calls: 1001, totals: { inputTokens: 5003 } });
  });
  it('merges older resource pages without replacing live state or moving the stream cursor', () => {
    const page = { snapshot_id: 'fixed', as_of: '5', expires_at: 10000, data: [], next_cursor: 'next', has_more: true };
    const view = new AgentSessionView({ ...empty, resource_pages: { items: page, required_actions: page } });
    view.apply(event(6, 'item.completed', { item_id: 'live', item: { content: 'latest' } }));
    view.apply(event(7, 'required_action.resolved', { request_id: 'resolved' }));
    view.mergeResourcePage('items', { ...page, has_more: false, data: [event(2, 'item.started', { item_id: 'live', item: { content: 'old' } }), event(3, 'item.completed', { item_id: 'history', item: { content: 'history' } })] });
    view.mergeResourcePage('required_actions', { ...page, has_more: false, data: [event(4, 'required_action.created', { request_id: 'resolved' })] });
    expect(view.snapshot().as_of).toBe('7');
    expect(view.snapshot().items.find(e => e.data.item_id === 'live')?.data.item).toEqual({ content: 'latest' });
    expect(view.snapshot().items).toHaveLength(2);
    expect(view.snapshot().required_actions).toEqual([]);
    expect(() => view.mergeResourcePage('items', { ...page, snapshot_id: 'other' })).toThrow('snapshot changed');
  });
  it('replays live failures, deletion and deltas over pages loaded after the stream', () => {
    const page = { snapshot_id: 'fixed', as_of: '5', expires_at: 10000, data: [], next_cursor: 'next', has_more: true };
    const view = new AgentSessionView({ ...empty, usage: { authoritative: true }, resource_pages: {
      items: page, tools: page, artifacts: page, required_actions: page,
    } });
    view.apply(event(6, 'item.delta', { item_id: 'partial', content: [{ type: 'text', text: ' suffix' }, { type: 'tool_use', id: 'call', content: '"x"}' }] }));
    view.apply(event(7, 'model.completed', { item_id: 'partial', model_call_id: 'failed', status: 'failed' }));
    view.apply(event(8, 'run.ended', { status: 'completed' }));
    view.apply(event(9, 'artifact.deleted', { artifact_id: 'deleted' }));
    view.apply(event(10, 'required_action.accepted', { request_id: 'accepted' }));
    view.mergeResourcePage('items', { ...page, data: [event(1, 'item.started', { item_id: 'partial', item: {
      role: 'assistant', status: 'in_progress', content: [{ type: 'text', text: 'prefix' }],
    } })] });
    view.mergeResourcePage('tools', { ...page, data: [event(2, 'tool.updated', { item_id: 'partial', tool_call_id: 'call', status: 'generating_arguments', arguments: '{"q":' })] });
    view.mergeResourcePage('artifacts', { ...page, data: [event(3, 'artifact.published', { artifact_id: 'deleted' })] });
    view.mergeResourcePage('required_actions', { ...page, data: [event(4, 'required_action.created', { request_id: 'accepted' })] });
    const result = view.snapshot();
    expect(result.items[0].data.item).toMatchObject({ status: 'incomplete', content: [{ type: 'text', text: 'prefix suffix' }] });
    expect(result.tools?.[0].data).toMatchObject({ status: 'unknown', arguments: '{"q":"x"}' });
    expect(result.artifacts).toEqual([]);
    expect(result.required_actions).toEqual([]);
    expect(result.as_of).toBe('10');
  });
  it('bounds live replay memory and asks for a fresh server snapshot', () => {
    const view = new AgentSessionView(empty);
    for (let i = 1; i <= 4096; i++) view.apply(event(i, 'informational', {}));
    expect(() => view.apply(event(4097, 'informational', {}))).toThrow(AgentViewLimitError);
    expect(view.snapshot().as_of).toBe('4096');
    expect(() => new AgentSessionView({ ...empty, usage: { large: 'x'.repeat(16 * 1024 * 1024) } })).toThrow(AgentViewLimitError);
  });
  it('restores nested graph steps by identity when different graphs repeat node names', () => {
    const updates = [
      event(1, 'step.updated', { step_id: 'root', graph_id: 'root', kind: 'graph', status: 'running' }),
      event(2, 'step.updated', { step_id: 'gate-a', graph_id: 'root', parent_step_id: 'root', name: 'gate', status: 'running' }),
      event(3, 'step.updated', { step_id: 'child', graph_id: 'child', parent_graph_id: 'root', parent_step_id: 'gate-a', kind: 'graph', status: 'running' }),
      event(4, 'step.updated', { step_id: 'gate-b', graph_id: 'child', parent_step_id: 'child', name: 'gate', status: 'waiting' }),
      event(5, 'step.updated', { step_id: 'gate-b', graph_id: 'child', parent_step_id: 'child', name: 'gate', status: 'succeeded' }),
    ];
    const full = new AgentSessionView(empty); updates.forEach(e => full.apply(e));
    for (let split = 1; split < updates.length; split++) {
      const prefix = new AgentSessionView(empty); updates.slice(0, split).forEach(e => prefix.apply(e));
      const restored = new AgentSessionView(prefix.snapshot());
      updates.slice(split).forEach(e => { restored.apply(e); restored.apply(e); });
      expect(restored.snapshot()).toEqual(full.snapshot());
    }
    expect(full.snapshot().steps).toHaveLength(4);
    expect(full.snapshot().steps?.find(e => e.data.step_id === 'gate-a')?.data.status).toBe('running');
    expect(full.snapshot().steps?.find(e => e.data.step_id === 'gate-b')?.data.status).toBe('succeeded');
  });
  it('converges after refreshing at every point during a message and tool loop', () => {
    const full = new AgentSessionView(empty); timeline.forEach(e => full.apply(e));
    for (let split = 0; split < timeline.length; split++) {
      const prefix = new AgentSessionView(empty); timeline.slice(0, split).forEach(e => prefix.apply(e));
      const restored = new AgentSessionView(prefix.snapshot());
      timeline.slice(split).forEach(e => { restored.apply(e); restored.apply(e); });
      expect(restored.snapshot()).toEqual(full.snapshot());
    }
    expect(full.snapshot().tools?.[0].data).toMatchObject({ status: 'success', arguments: '{"q":"test"}', input: { q: 'test' } });
    expect(full.snapshot().items).toHaveLength(2);
  });
  it('retains a failed partial response when a later attempt completes', () => {
    const view = new AgentSessionView(empty); timeline.slice(0, 2).forEach(e => view.apply(e));
    view.apply(event(3, 'model.completed', { item_id: 'm1', model_call_id: 'failed', status: 'failed' }));
    view.apply(event(4, 'run.ended', { status: 'completed' }));
    expect(view.snapshot().items[0].data.item).toMatchObject({ status: 'incomplete' });
    expect(view.snapshot().usage?.model_calls).toBe(1);
  });
  it('retains subtree usage across model completion and refresh', () => {
    const view = new AgentSessionView(empty);
    view.apply(event(1, 'usage.recorded', { model_call_id: 'child-call', childSessionId: 'child', usage: { inputTokens: 7, outputTokens: 3 } }));
    const restored = new AgentSessionView(view.snapshot());
    restored.apply(event(2, 'model.completed', { model_call_id: 'child-call', childSessionId: 'child', usage: { inputTokens: 7, outputTokens: 3 } }));
    restored.apply(event(3, 'usage.recorded', { model_call_id: 'parent-call', usage: { inputTokens: 2, outputTokens: 1 } }));
    expect(restored.snapshot().usage).toMatchObject({ scope: 'session_subtree', model_calls: 2, totals: { inputTokens: 9, outputTokens: 4 } });
  });
  it('restores rejected pending actions but never revives resolved ones', () => {
    const view = new AgentSessionView(empty);
    view.apply(event(1, 'required_action.created', { request_id: 'a', kind: 'confirmation' }));
    view.apply(event(2, 'required_action.accepted', { request_id: 'a', command_id: 'c' }));
    // A refresh loses the old form; rejection itself must carry enough detail to rebuild it.
    const restored = new AgentSessionView(view.snapshot());
    restored.apply(event(3, 'required_action.rejected', { request_id: 'a', command_id: 'c', pending: true, kind: 'confirmation', tool_call: { id: 'call' } }));
    expect(restored.snapshot().required_actions[0].data.kind).toBe('confirmation');
    restored.apply(event(4, 'required_action.resolved', { request_id: 'a' }));
    restored.apply(event(5, 'required_action.rejected', { request_id: 'a', pending: false }));
    expect(restored.snapshot().required_actions).toEqual([]);
  });
});
