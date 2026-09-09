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

import { describe, expect, it } from 'vitest';

import type { AgentTask } from '@/api/collaboration';
import type { ExecutionAttempt, OrchestrationRun } from '@/api/orchestration';
import { executionLabel, groupExecutions, isExecutionActive, matchesExecution } from './executions';

const run = (id: string, mode = 'direct', state: OrchestrationRun['state'] = 'running'): OrchestrationRun => ({
  id,
  tenant: 'tenant',
  namespace: 'default',
  rootIssueId: `issue-${id}`,
  mode,
  triggerType: 'assignment',
  state,
  version: 1,
  createdAt: id === 'newer' ? '2026-09-03T10:00:00Z' : '2026-09-03T09:00:00Z',
});

const task = (id: string, runId: string): AgentTask => ({
  id,
  tenant: 'tenant',
  namespace: 'default',
  issueId: `issue-${runId}`,
  orchestrationRunId: runId,
  runNodeId: `node-${id}`,
  agentId: 'coding-agent',
  status: 'running',
  priority: 1,
  triggerType: 'assignment',
  originator: { type: 'human' },
  version: 1,
  createdAt: '2026-09-03T09:01:00Z',
});

const attempt = (id: string, taskId: string, runId: string): ExecutionAttempt => ({
  id,
  agentTaskId: taskId,
  runId,
  nodeId: `node-${taskId}`,
  attempt: 1,
  dispatchGeneration: 1,
  backendKind: 'hosted-runtime',
  state: 'running',
  createdAt: '2026-09-03T09:02:00Z',
});

describe('execution projection', () => {
  it('groups tasks and attempts under runs and sorts newest first', () => {
    const groups = groupExecutions(
      [run('older'), run('newer')],
      [task('task-1', 'older')],
      [attempt('attempt-1', 'task-1', 'older')],
    );

    expect(groups.map((group) => group.run.id)).toEqual(['newer', 'older']);
    expect(groups[1].tasks[0].id).toBe('task-1');
    expect(groups[1].attempts[0].id).toBe('attempt-1');
  });

  it('flattens the direct one-task case in its user-facing label', () => {
    const [group] = groupExecutions([run('older')], [task('task-1', 'older')], []);
    expect(executionLabel(group)).toBe('Direct agent execution');
  });

  it('supports operational search across all execution layers', () => {
    const [group] = groupExecutions(
      [run('older')],
      [task('task-1', 'older')],
      [attempt('attempt-1', 'task-1', 'older')],
    );
    expect(matchesExecution(group, 'coding-agent')).toBe(true);
    expect(matchesExecution(group, 'hosted-runtime')).toBe(true);
    expect(matchesExecution(group, 'unrelated')).toBe(false);
    expect(isExecutionActive(group.run)).toBe(true);
    expect(isExecutionActive(run('done', 'direct', 'succeeded'))).toBe(false);
  });
});
