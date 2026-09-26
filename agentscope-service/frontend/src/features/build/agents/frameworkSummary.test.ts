import { expect, it } from 'vitest';
import type { AgentInstance } from '@/api/runtimeControl';
import { frameworkSummary } from './frameworkSummary';
it('groups registered frameworks and versions without guessing names and retains offline reports', () => {
  const instances = [
    { framework: 'agentscope', frameworkVersion: '1', health: 'healthy' },
    { framework: 'agentscope', frameworkVersion: '1', health: 'unhealthy' },
    { framework: 'adk', frameworkVersion: '2', health: 'unhealthy' },
    { agentName: 'DeepAgents', health: 'healthy' },
  ] as AgentInstance[];
  expect(frameworkSummary(instances)).toEqual([
    { label: 'ADK 2', online: false, count: 1 },
    { label: 'AgentScope 1', online: true, count: 2 },
    { label: 'Framework not reported', online: true, count: 1 },
  ]);
});
