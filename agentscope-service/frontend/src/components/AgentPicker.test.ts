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
