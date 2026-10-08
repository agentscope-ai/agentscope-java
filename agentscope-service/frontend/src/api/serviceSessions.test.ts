import { expect, it } from 'vitest';
import { ServiceView, type ServiceEvent, type ServiceSnapshot } from './serviceSessions';

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
