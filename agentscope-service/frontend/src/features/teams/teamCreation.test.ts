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
import type { AgentDefinition } from '@/api/agents';
import { buildInitialTeamMembers } from './teamCreation';

const agent = (id: string, name: string, agentKey: string): AgentDefinition => ({
  id,
  name,
  agentKey,
  status: 'active',
  runtimeKind: 'managed',
  scope: 'user',
  createdAt: 0,
  updatedAt: 0,
});

describe('buildInitialTeamMembers', () => {
  it('derives readable, unique roles without asking the user for internal fields', () => {
    const agents = [
      agent('a-1', 'Research Agent', 'research-agent'),
      agent('a-2', 'Research Agent 2', 'research-agent'),
      agent('a-3', 'Leader', 'leader'),
    ];

    expect(buildInitialTeamMembers(['a-1', 'a-2', 'a-3'], agents)).toEqual([
      { agentId: 'a-1', role: 'research-agent' },
      { agentId: 'a-2', role: 'research-agent-2' },
      { agentId: 'a-3', role: 'member' },
    ]);
  });
});
