export interface FileNode {
  name: string;
  path: string;
  type: 'file' | 'dir';
  size?: number;
  children?: FileNode[];
}

export interface WorkspaceSummary {
  agentId: string;
  workspacePath: string;
  exists: boolean;
  agentsMdExists: boolean;
  memoryMdExists: boolean;
  skillCount: number;
  subagentCount: number;
  dailyMemoryCount: number;
}

function base(agentId: string): string {
  return `/api/agents/${encodeURIComponent(agentId)}/workspace`;
}

export async function summary(agentId: string): Promise<WorkspaceSummary> {
  const res = await fetch(base(agentId));
  if (!res.ok) throw new Error('Failed to load workspace summary');
  return res.json();
}

export async function tree(agentId: string, recursive = true): Promise<FileNode[]> {
  const res = await fetch(`${base(agentId)}/files?recursive=${recursive}`);
  if (!res.ok) throw new Error('Failed to load workspace files');
  return res.json();
}

export async function readFile(agentId: string, path: string): Promise<string> {
  const res = await fetch(`${base(agentId)}/file?path=${encodeURIComponent(path)}`);
  if (!res.ok) throw new Error('Failed to read file');
  return res.text();
}
