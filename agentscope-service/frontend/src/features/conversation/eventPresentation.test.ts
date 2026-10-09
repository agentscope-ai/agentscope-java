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

import { expect, it } from 'vitest';
import { presentEvent } from './eventPresentation';
import { runtimeEventsToConversation } from './adapters';

it('labels verification as a versioned historical check rather than task completion', () => {
  const info = presentEvent({ id: 'check', type: 'span.task_verification', category: 'other', payload: {
    verification_id: 'v1', requirement_id: 'r1', action_id: 'a1', outcome: 'PASSED',
    evidence_binding: { taskId: 't1', contractVersion: 3, subjectVersion: 'hash1' },
  } });
  expect(info.title).toBe('Acceptance check recorded');
  expect(info.description).toContain('may become stale');
  expect(info.relations).toEqual(expect.arrayContaining([
    expect.objectContaining({ label: 'Checked version', value: 'hash1' }),
    expect.objectContaining({ label: 'Recorded outcome', value: 'PASSED' }),
  ]));
});

it('shows action facts without promoting them to task verification', () => {
  const info = presentEvent({ id: 'action', type: 'span.action_observation', category: 'other', payload: {
    action_id: 'a1', tool_call_id: 'call1', status: 'RETURNED',
    execution_details: { kind: 'shell', outcome: 'SUCCEEDED', exitCode: 0, outputTruncated: true },
  } });
  expect(info.title).toBe('Action recorded');
  expect(info.relations).toEqual(expect.arrayContaining([
    expect.objectContaining({ label: 'Exit code', value: '0' }),
  ]));
});

it('explains managed provenance and hides absent task identities', () => {
  const [event] = runtimeEventsToConversation([{ seq: 3, eventType: 'span.model_request_start', frameworkMeta: {
    managedEventId: 'evt-origin', managedSeq: 2, sourceKey: 'managed:evt-origin', turnId: '', attemptId: '', agentTaskId: '', dispatchGeneration: 0,
  } }]);
  const info = presentEvent(event);
  expect(info.title).toBe('Model request started');
  expect(info.source).toBe('Managed runtime');
  expect(info.relations).toEqual([]);
  expect(info.diagnostics.map(d => d.label)).toEqual(['Runtime event', 'Runtime sequence', 'Deduplication key']);
});

it('separates control-plane results and provides correct task links', () => {
  const [event] = runtimeEventsToConversation([{ eventType: 'assistant.message', frameworkMeta: {
    sourceKey: 'assistant:attempt-a', agentTaskId: 'task-a', attemptId: 'attempt-a', turnId: 'turn-a',
  } }]);
  const info = presentEvent(event);
  expect(info.source).toBe('Control plane');
  expect(info.title).toBe('Task result recorded');
  expect(info.relations.find(r => r.label === 'Task')?.href).toBe('/work/executions/tasks/task-a');
  expect(info.relations.find(r => r.label === 'Execution attempt')?.href).toBeUndefined();
});

it('keeps unknown provider events inspectable without inventing a source', () => {
  const info = presentEvent({ id: 'unknown', type: 'provider.custom_event', category: 'other', payload: { opaque: 42 } });
  expect(info.title).toBe('Provider custom event');
  expect(info.origin).toContain('producer is not identified');
});
