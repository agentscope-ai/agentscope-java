import { getToken } from './auth';

export interface ActiveTool {
  name: string;
  description?: string;
  source: 'built-in' | 'mcp' | string;
}

export interface ActiveToolsResponse {
  tools: ActiveTool[];
  warnings?: string[];
}

export interface BuiltinToolInfo {
  id: string;
  description?: string;
  group?: string;
}

export interface McpServerConfig {
  transport: 'stdio' | 'sse' | 'http' | 'streamable-http' | string;
  url?: string;
  command?: string;
  args?: string[];
  env?: Record<string, string>;
  headers?: Record<string, string>;
  queryParams?: Record<string, string>;
  enableTools?: string[];
  timeout?: string;
}

export interface ToolsConfig {
  allow?: string[];
  deny?: string[];
  mcpServers?: Record<string, McpServerConfig>;
}

export interface McpCatalogEntry {
  id: string;
  name: string;
  description?: string;
  transport: string;
  url?: string;
  command?: string;
  args?: string[];
  env?: Record<string, string>;
  headers?: Record<string, string>;
  queryParams?: Record<string, string>;
  requiredEnv?: string[];
  docsUrl?: string;
}

function authHeaders(): Record<string, string> {
  const token = getToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

function jsonHeaders(): Record<string, string> {
  return { ...authHeaders(), 'Content-Type': 'application/json' };
}

function base(agentId: string): string {
  return `/api/agents/${encodeURIComponent(agentId)}/tools`;
}

async function readError(res: Response, fallback: string): Promise<Error> {
  try {
    const body = await res.json();
    if (body && typeof body === 'object' && 'message' in body && typeof body.message === 'string') {
      return new Error(body.message);
    }
  } catch {
    // ignore
  }
  return new Error(`${fallback} (${res.status})`);
}

export async function fetchActive(agentId: string): Promise<ActiveToolsResponse> {
  const res = await fetch(`${base(agentId)}/active`, { headers: authHeaders() });
  if (!res.ok) throw await readError(res, 'Failed to load active tools');
  return res.json();
}

export async function fetchConfig(agentId: string): Promise<ToolsConfig> {
  const res = await fetch(`${base(agentId)}/config`, { headers: authHeaders() });
  if (!res.ok) throw await readError(res, 'Failed to load tools config');
  return res.json();
}

export async function saveConfig(agentId: string, cfg: ToolsConfig): Promise<ToolsConfig> {
  const res = await fetch(`${base(agentId)}/config`, {
    method: 'PUT',
    headers: jsonHeaders(),
    body: JSON.stringify(cfg),
  });
  if (!res.ok) throw await readError(res, 'Failed to save tools config');
  return res.json();
}

export async function fetchBuiltinCatalog(agentId: string): Promise<BuiltinToolInfo[]> {
  const res = await fetch(`${base(agentId)}/catalog/builtins`, { headers: authHeaders() });
  if (!res.ok) throw await readError(res, 'Failed to load built-in catalog');
  return res.json();
}

export async function fetchMcpCatalog(agentId: string): Promise<McpCatalogEntry[]> {
  const res = await fetch(`${base(agentId)}/catalog/mcp-servers`, { headers: authHeaders() });
  if (!res.ok) throw await readError(res, 'Failed to load MCP catalog');
  return res.json();
}
