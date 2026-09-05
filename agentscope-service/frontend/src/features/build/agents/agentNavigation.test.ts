import { describe, expect, it } from 'vitest';
import {
  agentDetailPath,
  agentDetailTabs,
  agentRuntimePath,
  resolveAgentDetailTab,
} from './agentNavigation';

describe('Agent detail navigation', () => {
  it('opens a Managed Agent in its canonical management detail', () => {
    expect(agentDetailPath({ id: 'managed/agent', runtimeKind: 'managed' }))
      .toBe('/agent-center/agents/managed%2Fagent/manage/settings');
  });

  it('opens other Agents in the canonical service detail', () => {
    expect(agentDetailPath({ id: 'external-agent', runtimeKind: 'external-application' }))
      .toBe('/agent-center/agents/external-agent');
  });

  it('only exposes Settings for Managed Agents', () => {
    expect(agentDetailTabs('managed').map(tab => tab.id)).toContain('settings');
    expect(agentDetailTabs('external-application').map(tab => tab.id)).not.toContain('settings');
    expect(agentDetailTabs('hosted-runtime').map(tab => tab.id)).not.toContain('settings');
  });

  it('labels External runtime controls as Integration', () => {
    expect(agentDetailTabs('external-application').find(tab => tab.id === 'runtime')?.label)
      .toBe('Integration');
    expect(agentDetailTabs('managed').find(tab => tab.id === 'runtime')?.label)
      .toBe('Runtime');
  });

  it('falls back from non-Managed Settings to runtime controls', () => {
    expect(resolveAgentDetailTab('settings', 'external-application')).toBe('runtime');
    expect(resolveAgentDetailTab('settings', 'hosted-runtime')).toBe('runtime');
    expect(resolveAgentDetailTab('settings', 'managed')).toBe('settings');
    expect(agentRuntimePath('external/agent')).toBe('/agent-center/agents/external%2Fagent?tab=runtime');
  });
});
