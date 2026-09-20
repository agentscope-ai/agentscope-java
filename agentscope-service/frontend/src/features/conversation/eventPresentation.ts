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

/* Copyright 2024-2026 the original author or authors. Licensed under the Apache License, Version 2.0. */
import type { ConversationEvent } from './model';

export function record(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {};
}

export function durationLabel(ms: number): string {
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`;
}

export interface EventRelation { label: string; value: string; description: string; href?: string }
export function presentEvent(event: ConversationEvent) {
  const raw = record(event.payload);
  const meta = record(raw.frameworkMeta);
  const type = event.type;
  const sourceKey = String(meta.sourceKey || '');
  const managed = !!meta.managedEventId || sourceKey.startsWith('managed:') || event.id.startsWith('managed-event-');
  const projected = /^(assistant|turn-completed|turn-failed|user|dispatch):/.test(sourceKey);
  const source = managed ? 'Managed runtime' : projected ? 'Control plane' : meta.provider ? String(meta.provider) : 'Session event log';
  const origin = managed
    ? 'Emitted by the managed agent runtime and stored in the session event log.'
    : projected ? 'Written by the control plane while recording task input, output or execution status.'
    : 'Recorded in the session event log. The producer is not identified in this record.';
  const tool = String(raw.toolName || raw.name || 'Tool');
  const labels: Record<string, [string, string]> = {
    'span.model_request_start': ['Model request started', 'The agent sent a request to the model. This marks a request boundary, not thinking content.'],
    'span.model_request_end': ['Model request finished', 'The model request ended. Tool execution or another model request may follow.'],
    'session.status_running': ['Session running', 'The runtime is processing this turn.'],
    'session.status_idle': ['Session idle', 'The runtime finished processing and is waiting for input. This does not indicate that the issue was accepted.'],
    'session.status_requires_action': ['Waiting for action', 'The runtime needs an external action before it can continue.'],
    'session.requires_action': ['Action required', 'The runtime is waiting for input or tool approval.'],
    'session.interrupted': ['Session interrupted', 'The current processing was interrupted.'],
    'session.status_terminated': ['Session terminated', 'The runtime has stopped this session.'],
    'session.error': ['Session failed', 'The runtime reported an error while processing this turn.'],
    'user.message': ['Input received', 'Input was delivered to the agent. Task sessions may contain a system-generated task instruction in the user role.'],
    'agent.message': ['Agent response', 'Response content emitted by the agent runtime.'],
    'assistant.message': projected ? ['Task result recorded', 'The control plane recorded the task result in this session.'] : ['Assistant response', 'Assistant response content recorded in this session.'],
    'agent.thinking': ['Thinking recorded', 'Reasoning content exposed by the model/runtime. This is separate from the final response.'],
    'turn.completed': ['Turn completed', 'The execution turn was marked complete. Other runtime events can still arrive afterward.'],
    'turn.failed': ['Turn failed', 'The execution turn was marked failed.'],
  };
  let [title, description] = labels[type] || [type.replace(/[._]/g, ' ').replace(/^\w/, c => c.toUpperCase()), 'A runtime event was recorded. Expand the original record for provider-specific details.'];
  if (event.category === 'tool' || (event.category === 'error' && /tool/.test(type))) {
    const result = /result|end/.test(type);
    title = `${tool} · ${result ? event.category === 'error' ? 'failed' : 'result received' : 'called'}`;
    description = result ? 'Tool output returned to the agent. The call ID connects this result to its input.' : 'The agent requested a tool. Its result is paired with the call in Conversation.';
  }
  const relations: EventRelation[] = [];
  const add = (label: string, value: unknown, description: string, prefix?: string) => {
    if (value == null || value === '' || value === 0) return;
    relations.push({ label, value: String(value), description, href: prefix ? `${prefix}${encodeURIComponent(String(value))}` : undefined });
  };
  add('Session', raw.sessionFk, 'The session containing this event.', '/work/sessions/');
  add('Task', meta.agentTaskId, 'The unit of work assigned to the agent.', '/work/executions/tasks/');
  add('Execution attempt', meta.attemptId, 'One attempt to execute the task; a retry has a different attempt ID.');
  add('Turn', meta.turnId, 'The processing turn within this session.');
  add('Tool call', event.callId, 'Matches the tool request with its result.');
  add('Dispatch generation', meta.dispatchGeneration, 'The dispatch version used to reject events from an older attempt.');
  add('Endpoint invocation', meta.endpointInvocationId, 'The API request that initiated this work.');
  const diagnostics: EventRelation[] = [];
  if (meta.managedEventId) diagnostics.push({ label: 'Runtime event', value: String(meta.managedEventId), description: 'The original event ID allocated by the managed runtime.' });
  if (meta.managedSeq != null) diagnostics.push({ label: 'Runtime sequence', value: String(meta.managedSeq), description: 'Position in the runtime log. Session sequence also includes control-plane records and can differ.' });
  if (sourceKey) diagnostics.push({ label: 'Deduplication key', value: sourceKey, description: 'Prevents the same source event from being stored twice. This is not a separate task or execution.' });
  return { title, description, source, origin, relations, diagnostics };
}
