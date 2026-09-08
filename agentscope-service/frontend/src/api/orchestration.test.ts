import { describe, it, expect } from 'vitest';
import { normalizeRunGraph, type RunGraph } from './orchestration';

describe('Workflow graph responses', () => {
  it('supports gates-only executions whose empty task and attempt fields are omitted', () => {
    const graph = normalizeRunGraph({run: {id: 'gates'}, nodes: [], edges: []} as unknown as RunGraph);
    expect(graph.tasks.filter(task => task.runNodeId === 'gate')).toEqual([]);
    expect(graph.attempts).toEqual([]);
    expect(graph.childRuns).toEqual([]);
  });
  it('preserves returned execution records', () => {
    const tasks = [{id: 'task'}];
    expect(normalizeRunGraph({tasks} as unknown as RunGraph).tasks).toBe(tasks);
  });
});
