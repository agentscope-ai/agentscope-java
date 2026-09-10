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

/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

import type { AgentTask } from '@/api/collaboration';
import type { ExecutionAttempt, OrchestrationRun } from '@/api/orchestration';

export type ExecutionGroup = {
  run: OrchestrationRun;
  tasks: AgentTask[];
  attempts: ExecutionAttempt[];
};

const terminalRunStates = new Set(['succeeded', 'partial_succeeded', 'failed', 'cancelled']);

export function isExecutionActive(run: OrchestrationRun): boolean {
  return !terminalRunStates.has(run.state);
}

export function groupExecutions(
  runs: OrchestrationRun[],
  tasks: AgentTask[],
  attempts: ExecutionAttempt[],
): ExecutionGroup[] {
  const tasksByRun = new Map<string, AgentTask[]>();
  const attemptsByRun = new Map<string, ExecutionAttempt[]>();

  for (const task of tasks) {
    const current = tasksByRun.get(task.orchestrationRunId) || [];
    current.push(task);
    tasksByRun.set(task.orchestrationRunId, current);
  }
  for (const attempt of attempts) {
    const current = attemptsByRun.get(attempt.runId) || [];
    current.push(attempt);
    attemptsByRun.set(attempt.runId, current);
  }

  return [...runs]
    .sort((left, right) => Date.parse(right.createdAt) - Date.parse(left.createdAt))
    .map((run) => ({
      run,
      tasks: (tasksByRun.get(run.id) || []).sort(
        (left, right) => Date.parse(left.createdAt) - Date.parse(right.createdAt),
      ),
      attempts: (attemptsByRun.get(run.id) || []).sort(
        (left, right) => left.attempt - right.attempt,
      ),
    }));
}

export function executionLabel(group: ExecutionGroup): string {
  if (group.run.mode === 'direct' && group.tasks.length === 1) return 'Direct agent execution';
  if (group.run.mode === 'adaptive') return 'Team execution';
  if (group.run.mode === 'declared') return 'Workflow execution';
  if (group.run.mode === 'subrun') return 'Sub-execution';
  return 'Execution';
}

export function matchesExecution(group: ExecutionGroup, search: string): boolean {
  const needle = search.trim().toLowerCase();
  if (!needle) return true;
  return [
    group.run.id,
    group.run.rootIssueId,
    group.run.mode,
    group.run.state,
    group.run.triggerType,
    ...group.tasks.flatMap((task) => [task.id, task.agentId, task.teamRole, task.status]),
    ...group.attempts.flatMap((attempt) => [attempt.id, attempt.backendKind, attempt.state, attempt.hostId]),
  ]
    .filter(Boolean)
    .join(' ')
    .toLowerCase()
    .includes(needle);
}
