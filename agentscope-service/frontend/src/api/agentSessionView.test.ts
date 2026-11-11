import { describe, it, expect } from 'vitest';
import { AgentSessionView } from './agentSessionView';
import type { AgentSessionEvent, AgentSessionSnapshot } from './agentSessions';
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
