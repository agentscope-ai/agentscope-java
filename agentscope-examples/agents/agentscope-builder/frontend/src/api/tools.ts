import { getToken } from './auth';
import {
  AgentDefinition,
  AgentToolset,
  McpServerSpec,
  getAgent,
  updateAgent,
} from './agents';

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

/** Enabled built-in tool ids from Agent body `tools[]` (agent_toolset). */
export function computeEnabledBuiltins(
  catalog: BuiltinToolInfo[],
  tools: AgentToolset[] | undefined,
): Set<string> {
  const agentTs = (tools ?? []).find(t => t.type === 'agent_toolset');
  if (!agentTs || !agentTs.configs || agentTs.configs.length === 0) {
    return new Set(catalog.map(b => b.id));
  }
  const defaultEnabled = agentTs.defaultConfig?.enabled !== false;
  const named = new Map<string, boolean>();
  for (const c of agentTs.configs) {
    if (!c?.name) continue;
    named.set(c.name, c.enabled != null ? !!c.enabled : defaultEnabled);
  }
  const out = new Set<string>();
  for (const b of catalog) {
    if (named.has(b.id)) {
      if (named.get(b.id)) out.add(b.id);
    } else if (defaultEnabled) {
      out.add(b.id);
    }
  }
  return out;
}

function requireVersion(agent: AgentDefinition): number {
  if (agent.version == null) {
    throw new Error('Agent version missing; cannot update (optimistic lock)');
  }
  return agent.version;
}

/** Persist built-in enablement via PUT /api/agents/{id} (tools body). */
export async function saveBuiltinEnabled(
  agentId: string,
  catalog: BuiltinToolInfo[],
  enabled: Set<string>,
): Promise<AgentDefinition> {
  const agent = await getAgent(agentId);
  const other = (agent.tools ?? []).filter(t => t.type !== 'agent_toolset');
  const agentToolset: AgentToolset = {
    type: 'agent_toolset',
    defaultConfig: { enabled: true, permissionPolicy: { type: 'always_allow' } },
    configs: catalog.map(b => ({
      name: b.id,
      enabled: enabled.has(b.id),
      permissionPolicy: { type: 'always_allow' },
    })),
  };
  return updateAgent(agentId, {
    name: agent.name,
    version: requireVersion(agent),
    tools: [agentToolset, ...other],
  });
}

/** Add/replace an MCP server on Agent body `mcpServers` + `mcp_toolset`. */
export async function installMcpServer(
  agentId: string,
  name: string,
  server: McpServerConfig,
): Promise<AgentDefinition> {
  const agent = await getAgent(agentId);
  const mcpServers: McpServerSpec[] = [
    ...(agent.mcpServers ?? []).filter(s => s.name !== name),
    {
      name,
      type: server.url ? 'url' : 'stdio',
      transport: server.transport,
      url: server.url,
      command: server.command,
      args: server.args,
      env: server.env,
      headers: server.headers,
      queryParams: server.queryParams,
      enableTools: server.enableTools,
      timeout: server.timeout,
    },
  ];
  const tools: AgentToolset[] = [
    ...(agent.tools ?? []).filter(
      t => !(t.type === 'mcp_toolset' && t.mcpServerName === name),
    ),
    {
      type: 'mcp_toolset',
      mcpServerName: name,
      defaultConfig: { enabled: true, permissionPolicy: { type: 'always_allow' } },
    },
  ];
  return updateAgent(agentId, {
    name: agent.name,
    version: requireVersion(agent),
    tools,
    mcpServers,
  });
}

/** Disable a built-in or remove an MCP server via Agent body. */
export async function disableConfiguredTool(
  agentId: string,
  tool: ActiveTool,
): Promise<AgentDefinition> {
  const agent = await getAgent(agentId);
  if (tool.source === 'built-in') {
    const agentTs = (agent.tools ?? []).find(t => t.type === 'agent_toolset');
    const configs = [...(agentTs?.configs ?? [])];
    const idx = configs.findIndex(c => c.name === tool.name);
    if (idx >= 0) {
      configs[idx] = { ...configs[idx], enabled: false };
    } else {
      configs.push({
        name: tool.name,
        enabled: false,
        permissionPolicy: { type: 'always_allow' },
      });
    }
    const other = (agent.tools ?? []).filter(t => t.type !== 'agent_toolset');
    const next: AgentToolset = {
      type: 'agent_toolset',
      defaultConfig: agentTs?.defaultConfig ?? {
        enabled: true,
        permissionPolicy: { type: 'always_allow' },
      },
      configs,
    };
    return updateAgent(agentId, {
      name: agent.name,
      version: requireVersion(agent),
      tools: [next, ...other],
    });
  }

  const mcpName = tool.source.startsWith('mcp:') ? tool.source.slice(4) : tool.name;
  return updateAgent(agentId, {
    name: agent.name,
    version: requireVersion(agent),
    tools: (agent.tools ?? []).filter(
      t => !(t.type === 'mcp_toolset' && t.mcpServerName === mcpName),
    ),
    mcpServers: (agent.mcpServers ?? []).filter(s => s.name !== mcpName),
  });
}

/**
 * Configured (not live-introspected) active tools from Agent body.
 * Replaces removed GET …/tools/active.
 */
export async function fetchConfiguredActive(
  agentId: string,
  catalog?: BuiltinToolInfo[],
): Promise<ActiveToolsResponse> {
  const [agent, builtins] = await Promise.all([
    getAgent(agentId),
    catalog ? Promise.resolve(catalog) : fetchBuiltinCatalog(agentId),
  ]);
  const enabled = computeEnabledBuiltins(builtins, agent.tools);
  const tools: ActiveTool[] = builtins
    .filter(b => enabled.has(b.id))
    .map(b => ({
      name: b.id,
      description: b.description,
      source: 'built-in',
    }));

  for (const s of agent.mcpServers ?? []) {
    tools.push({
      name: s.name,
      description: s.url || s.command || s.transport,
      source: `mcp:${s.name}`,
    });
  }

  return {
    tools,
    warnings: [
      'Active list is derived from Agent body (tools / mcpServers). Live MCP tool introspection was removed with GET …/tools/active.',
    ],
  };
}

/** MCP server names currently installed on the agent. */
export async function listInstalledMcpNames(agentId: string): Promise<{
  agent: AgentDefinition;
  names: Set<string>;
}> {
  const agent = await getAgent(agentId);
  return {
    agent,
    names: new Set((agent.mcpServers ?? []).map(s => s.name)),
  };
}
