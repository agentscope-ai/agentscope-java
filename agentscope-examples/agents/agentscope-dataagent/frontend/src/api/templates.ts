import { getToken } from './auth';

export interface TemplateSummary {
  id: string;
  name: string;
  description: string;
  tags: string[];
  agentsMdPreview: string;
}

export interface TemplateFile {
  path: string;
  content: string;
}

export interface TemplateDetail {
  id: string;
  name: string;
  description: string;
  tags: string[];
  files: TemplateFile[];
}

function authHeaders(): Record<string, string> {
  const token = getToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

export async function listTemplates(): Promise<TemplateSummary[]> {
  const res = await fetch('/api/templates', { headers: authHeaders() });
  if (!res.ok) throw new Error('Failed to load templates');
  return res.json();
}

export async function getTemplate(id: string): Promise<TemplateDetail> {
  const res = await fetch(`/api/templates/${encodeURIComponent(id)}`, {
    headers: authHeaders(),
  });
  if (!res.ok) throw new Error('Failed to load template');
  return res.json();
}
