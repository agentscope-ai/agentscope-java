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

export type CapabilityDestination =
  | 'overview'
  | 'sessions'
  | 'related-work'
  | 'subagent-inventory'
  | 'workspace-inventory';

export interface CapabilityPresentation {
  title: string;
  description: string;
  category: 'Observability' | 'Session control' | 'Work and tasks' | 'Runtime inventory' | 'Extension';
  actionLabel?: string;
  destination?: CapabilityDestination;
}

const CAPABILITY_PRESENTATIONS: Record<string, CapabilityPresentation> = {
  'session-reporting': {
    title: 'Session visibility',
    description: 'Reports live and historical sessions so operators can inspect runtime activity.',
    category: 'Observability',
    actionLabel: 'Open sessions',
    destination: 'sessions',
  },
  'event-reporting': {
    title: 'Runtime event stream',
    description: 'Reports runtime events used by service signals and operational diagnostics.',
    category: 'Observability',
    actionLabel: 'View telemetry',
    destination: 'overview',
  },
  'context-reporting': {
    title: 'Context telemetry',
    description: 'Reports context usage and pressure for active runtime sessions.',
    category: 'Observability',
    actionLabel: 'View telemetry',
    destination: 'overview',
  },
  'context-query': {
    title: 'Live context inspection',
    description: 'Lets operators inspect the effective model context of a selected session.',
    category: 'Observability',
    actionLabel: 'Choose a session',
    destination: 'sessions',
  },
  'message-query': {
    title: 'Message history',
    description: 'Lets operators inspect messages reported by a selected runtime session.',
    category: 'Observability',
    actionLabel: 'Choose a session',
    destination: 'sessions',
  },
  'export-transcript': {
    title: 'Transcript export',
    description: 'The runtime can export transcripts, but the console has no dedicated export action yet.',
    category: 'Observability',
  },
  'session-command': {
    title: 'Session lifecycle control',
    description: 'Allows supported live sessions to be compressed or terminated.',
    category: 'Session control',
    actionLabel: 'Manage sessions',
    destination: 'sessions',
  },
  'session-abort': {
    title: 'Turn interruption',
    description: 'Allows an operator to abort the active turn in a supported session.',
    category: 'Session control',
    actionLabel: 'Manage sessions',
    destination: 'sessions',
  },
  'plan-mode': {
    title: 'Plan mode control',
    description: 'Allows an operator to enter or exit plan mode for a supported session.',
    category: 'Session control',
    actionLabel: 'Manage sessions',
    destination: 'sessions',
  },
  'task-query': {
    title: 'Session todo inspection',
    description: 'Exposes the todo or task state associated with a selected session.',
    category: 'Work and tasks',
    actionLabel: 'Choose a session',
    destination: 'sessions',
  },
  'agent-task': {
    title: 'AgentTask execution',
    description: 'Allows this Agent to receive and execute control-plane AgentTasks.',
    category: 'Work and tasks',
    actionLabel: 'View Issue tasks',
    destination: 'related-work',
  },
  'subagent-task-query': {
    title: 'Background task inspection',
    description: 'Exposes subagent and background task state for a selected session.',
    category: 'Work and tasks',
    actionLabel: 'Choose a session',
    destination: 'sessions',
  },
  'subagent-task-command': {
    title: 'Background task control',
    description: 'The runtime accepts background task commands, but the console has no dedicated control yet.',
    category: 'Work and tasks',
  },
  'subagent-inventory': {
    title: 'Subagent inventory',
    description: 'Reports the subagents currently exposed by each runtime instance.',
    category: 'Runtime inventory',
    actionLabel: 'View inventory',
    destination: 'subagent-inventory',
  },
  'workspace-inventory': {
    title: 'Workspace inventory',
    description: 'Reports runtime-owned workspaces and their access modes.',
    category: 'Runtime inventory',
    actionLabel: 'View inventory',
    destination: 'workspace-inventory',
  },
};

function humanizeCapability(capability: string) {
  return capability
    .split(/[-_]/)
    .filter(Boolean)
    .map(word => word.charAt(0).toUpperCase() + word.slice(1))
    .join(' ');
}

export function describeAgentCapability(capability: string): CapabilityPresentation {
  return CAPABILITY_PRESENTATIONS[capability] ?? {
    title: humanizeCapability(capability) || 'Runtime extension',
    description: 'This runtime-specific capability has no dedicated console workflow mapped yet.',
    category: 'Extension',
  };
}
