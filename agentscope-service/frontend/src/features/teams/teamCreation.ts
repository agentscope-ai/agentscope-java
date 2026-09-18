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

import type { AgentDefinition } from '@/api/agents';

export function buildInitialTeamMembers(agentIds: string[], agents: AgentDefinition[]) {
  const usedRoles = new Set<string>();
  return agentIds.map((agentId, index) => {
    const agent = agents.find(item => item.id === agentId);
    const source = agent?.agentKey || agent?.name || `member-${index + 1}`;
    const normalized = source
      .trim()
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-+|-+$/g, '') || `member-${index + 1}`;
    const base = normalized === 'leader' ? 'member' : normalized;
    let role = base;
    let suffix = 2;
    while (usedRoles.has(role)) role = `${base}-${suffix++}`;
    usedRoles.add(role);
    return { agentId, role };
  });
}
