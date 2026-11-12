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
import { filterAgentOptions } from './AgentPicker';
import type { AgentDefinition } from '@/api/agents';

const agent = (id: string, name: string, status: string, runtimeKind: string): AgentDefinition => ({
  id,
  name,
  status,
  runtimeKind,
  agentKey: name.toLowerCase().replace(/ /g, '-'),
  scope: 'global',
  createdAt: 0,
  updatedAt: 0,
});

describe('filterAgentOptions', () => {
  const agents = [
    agent('a-1', 'Billing Agent', 'active', 'external-application'),
    agent('a-2', 'Research Agent', 'disabled', 'managed'),
    agent('a-3', 'Code Agent', 'active', 'hosted-runtime'),
  ];

  it('only offers active registered Agents by default', () => {
    expect(filterAgentOptions(agents, '', false, '', new Set()).map((item) => item.id))
      .toEqual(['a-1', 'a-3']);
  });

  it('keeps an existing inactive selection visible', () => {
    expect(filterAgentOptions(agents, '', false, 'a-2', new Set()).map((item) => item.id))
      .toContain('a-2');
  });

  it('searches display name, key, id, and runtime kind', () => {
    expect(filterAgentOptions(agents, 'hosted', true, '', new Set()).map((item) => item.id))
      .toEqual(['a-3']);
    expect(filterAgentOptions(agents, 'billing-agent', true, '', new Set()).map((item) => item.id))
      .toEqual(['a-1']);
  });
});
