import type { AgentInstance } from '@/api/runtimeControl';

const labels: Record<string, string> = { agentscope: 'AgentScope', 'agentscope-java': 'AgentScope Java', deepagents: 'DeepAgents', 'deep-agents': 'DeepAgents', adk: 'ADK', 'google-adk': 'Google ADK' };
export function frameworkSummary(instances: AgentInstance[]): { label: string; online: boolean; count: number }[] {
  const groups = new Map<string, { label: string; online: boolean; count: number }>();
  for (const instance of instances) {
    const framework = instance.framework?.trim();
    const name = framework ? labels[framework.toLowerCase()] || framework : 'Framework not reported';
    const label = name + (framework && instance.frameworkVersion ? ` ${instance.frameworkVersion}` : '');
    const existing = groups.get(label) || { label, online: false, count: 0 };
    existing.online ||= instance.health === 'healthy';
    existing.count++;
    groups.set(label, existing);
  }
  return [...groups.values()].sort((a, b) => a.label.localeCompare(b.label));
}
