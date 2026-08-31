import { describe, expect, it } from 'vitest';
import { describeAgentCapability } from './agentCapabilityPresentation';

describe('agent capability presentation', () => {
  it('maps an operable capability to its console destination', () => {
    expect(describeAgentCapability('session-abort')).toMatchObject({
      title: 'Turn interruption',
      category: 'Session control',
      actionLabel: 'Manage sessions',
      destination: 'sessions',
    });
  });

  it('keeps unknown runtime extensions visible without inventing an action', () => {
    expect(describeAgentCapability('custom_runtime_feature')).toEqual({
      title: 'Custom Runtime Feature',
      description: 'This runtime-specific capability has no dedicated console workflow mapped yet.',
      category: 'Extension',
    });
  });

  it('does not expose an action before the console implements the workflow', () => {
    expect(describeAgentCapability('export-transcript')).not.toHaveProperty('destination');
    expect(describeAgentCapability('subagent-task-command')).not.toHaveProperty('actionLabel');
  });
});
