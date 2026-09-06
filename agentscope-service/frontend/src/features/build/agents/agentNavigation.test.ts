import { describe, expect, it } from 'vitest';
import {
  agentDetailPath,
  agentDetailTabs,
  agentServicePath,
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

  it('folds runtime information into Overview', () => {
    expect(agentDetailTabs('external-application').map(tab => tab.id)).not.toContain('runtime');
    expect(agentDetailTabs('managed').map(tab => tab.id)).not.toContain('runtime');
  });

  it('falls back from non-Managed Settings to the consolidated Overview', () => {
    expect(resolveAgentDetailTab('settings', 'external-application')).toBe('overview');
    expect(resolveAgentDetailTab('settings', 'hosted-runtime')).toBe('overview');
    expect(resolveAgentDetailTab('settings', 'managed')).toBe('settings');
    expect(agentServicePath('external/agent')).toBe('/agent-center/agents/external%2Fagent');
  });
});
