/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
