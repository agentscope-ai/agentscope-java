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

export async function listTemplates(): Promise<TemplateSummary[]> {
  const res = await fetch('/api/templates');
  if (!res.ok) throw new Error('Failed to load templates');
  return res.json();
}

export async function getTemplate(id: string): Promise<TemplateDetail> {
  const res = await fetch(`/api/templates/${encodeURIComponent(id)}`);
  if (!res.ok) throw new Error('Failed to load template');
  return res.json();
}
