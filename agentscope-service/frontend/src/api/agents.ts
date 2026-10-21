import { namespaceHeaders } from "@/lib/namespaceScope";
/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { getToken } from './auth';
import { readApiError } from './http';

export type ShareTier = 'CLONE' | 'RUN' | 'EDIT';
export type GranteeType = 'USER' | 'WORKSPACE';

export interface AgentShareGrant {
  granteeType: GranteeType;
  granteeId: string;
  tier: ShareTier;
  createdAt: number;
  createdBy: string;
}

/** Matches backend AgentSpecTypes.PermissionPolicy */
export interface PermissionPolicy {
  type: 'always_allow' | 'always_ask' | 'deny' | string;
}

export interface ToolDefaultConfig {
  enabled?: boolean;
  permissionPolicy?: PermissionPolicy;
}

export interface ToolConfigEntry {
  name: string;
  enabled?: boolean;
  permissionPolicy?: PermissionPolicy;
}

/** Matches backend AgentSpecTypes.AgentToolset */
export interface AgentToolset {
  type: 'agent_toolset' | 'mcp_toolset' | string;
  defaultConfig?: ToolDefaultConfig;
  configs?: ToolConfigEntry[];
  mcpServerName?: string;
}

/** Matches backend AgentSpecTypes.McpServerSpec */
export interface McpServerSpec {
  name: string;
  type?: string;
  url?: string;
  transport?: string;
  command?: string;
  args?: string[];
  env?: Record<string, string>;
  headers?: Record<string, string>;
  queryParams?: Record<string, string>;
  enableTools?: string[];
  disableTools?: string[];
  required?: boolean;
  initializationTimeout?: string;
  timeout?: string;
}

export interface SkillRef {
  type?: string;
  name?: string;
  id?: string;
  version?: string;
}

export interface WorkspaceBinding { version: number; digest?: string; overrides: string[]; instructions?: string }

export interface AgentDefinition {
 workspaceBinding?: WorkspaceBinding | null;
 workspaceVersion?: number;
 definitionDigest?: string;
  id: string;
  name: string;
  description?: string;
  /** System prompt (API field name is `system`, not sysPrompt). */
  system?: string;
  model?: string;
  maxIters?: number;
  tools?: AgentToolset[];
  mcpServers?: McpServerSpec[];
  skills?: SkillRef[];
  scope: 'global' | 'user';
  ownerId?: string;
  createdAt: number;
  updatedAt: number;
  shares?: AgentShareGrant[];
  runAs?: string;
  forkOf?: string;
  workspacePath?: string;
  workspaceId?: string | null;
  /** Preferred environment for new sessions when caller omits environmentId. */
  defaultEnvironmentId?: string | null;
  /** Vaults auto-mounted on new sessions when caller omits vaultIds. */
  defaultVaultIds?: string[];
  /** Memory stores auto-mounted on new sessions when caller omits memoryStoreIds. */
  defaultMemoryStoreIds?: string[];
  tierForCurrentUser?: ShareTier;
  version?: number;
  archivedAt?: number | null;
  agentKey?: string;
  status?: string;
  runtimeKind?: 'managed' | 'external-application' | 'hosted-runtime' | string;
  catalogVersion?: number;
  tenant?: string;
  namespace?: string;
  catalogCapabilities?: unknown;
  catalogLabels?: Record<string, unknown>;
  catalogMetadata?: Record<string, unknown>;
}

export interface AgentVersionEntry {
  version: number;
  snapshot?: Record<string, unknown>;
  createdAt: number;
}


export interface AgentCreateRequest {
 workspaceBinding?: WorkspaceBinding | null;
  id?: string;
  tenant?: string;
  namespace?: string;
  agentKey?: string;
  runtimeKind?: 'managed' | 'hosted-runtime';
  runtimeProfileId?: string;
  runtimePoolId?: string;
  name: string;
  description?: string;
  system?: string;
  model?: string;
  maxIters?: number;
  tools?: AgentToolset[];
  mcpServers?: McpServerSpec[];
  skills?: SkillRef[];
  workspacePath?: string;
  /** First-class Workspace to link (skills/tools/AGENTS.md source). */
  workspaceId?: string;
  defaultEnvironmentId?: string | null;
  defaultVaultIds?: string[];
  defaultMemoryStoreIds?: string[];
  /** Required on PUT for optimistic locking. */
  version?: number;
}

function authHeaders() {
  return {
    'Content-Type': 'application/json',
    ...namespaceHeaders(),
    Authorization: `Bearer ${getToken()}`,
  };
}

export async function listAgents(tenant = 'default', namespace = 'default'): Promise<AgentDefinition[]> {
  const params = new URLSearchParams({ tenant, namespace });
  const res = await fetch(`/api/v1/agents?${params}`, { headers: authHeaders() });
  if (!res.ok) throw await readApiError(res, 'Failed to list agents');
  const body = await res.json() as { items?: CatalogAgent[] };
  return Promise.all((body.items ?? []).map(async (agent) => {
    let runtimeKind = '';
    try {
      const bindings = await listCatalogBindings(agent.id);
      runtimeKind = bindings.find(binding => binding.enabled)?.kind ?? bindings[0]?.kind ?? '';
    } catch {
      // The identity remains useful even when binding details are unavailable.
    }
    return catalogToDefinition(agent, runtimeKind);
  }));
}

export async function getAgent(id: string): Promise<AgentDefinition> {
  const [catalogRes, definitionRes, bindings] = await Promise.all([
    fetch(`/api/v1/agents/${encodeURIComponent(id)}`, { headers: authHeaders() }),
    fetch(`/api/v1/agents/${encodeURIComponent(id)}/definition`, { headers: authHeaders() }),
    listCatalogBindings(id),
  ]);
  if (!catalogRes.ok) throw await readApiError(catalogRes, 'Failed to load agent');
  const catalogBody = await catalogRes.json() as { agent: CatalogAgent };
  if (!definitionRes.ok) {
    if (definitionRes.status === 404) {
      return catalogToDefinition(catalogBody.agent, bindings.find(binding => binding.enabled)?.kind ?? bindings[0]?.kind ?? '');
    }
    throw await readApiError(definitionRes, 'Failed to load Agent definition');
  }
  const definitionBody = await definitionRes.json() as { definition: AgentDefinition };
  return mergeCatalogDefinition(catalogBody.agent, definitionBody.definition, bindings);
}

export async function createAgent(req: AgentCreateRequest): Promise<AgentDefinition> {
  const agentKey = req.agentKey || (req.name || 'agent').trim().toLowerCase()
    .replace(/[^a-z0-9_-]+/g, '-')
    .replace(/^-+|-+$/g, '') + `-${crypto.randomUUID().slice(0, 8)}`;
  const runtimeKind = req.runtimeKind ?? 'managed';
  const configuration = runtimeKind === 'hosted-runtime'
    ? { runtimeProfileId: req.runtimeProfileId, runtimePoolId: req.runtimePoolId }
    : undefined;
  const res = await fetch('/api/v1/agents', {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify({
      tenant: req.tenant ?? 'default',
      namespace: req.namespace ?? 'default',
      agentKey,
      displayName: req.name,
      description: req.description,
      ownerType: 'user',
      binding: { kind: runtimeKind, priority: 100, configuration },
      // Agent behavior is portable. The control plane stores one definition
      // for Managed and Hosted runtimes; only the execution binding differs.
      definition: req,
    }),
  });
  if (!res.ok) throw await readApiError(res, 'Failed to create agent');
  const body = await res.json() as { agent: CatalogAgent; definition?: AgentDefinition; binding?: CatalogBinding };
  const bindings = body.binding ? [body.binding] : [];
  return body.definition
    ? mergeCatalogDefinition(body.agent, body.definition, bindings)
    : catalogToDefinition(body.agent, body.binding?.kind ?? runtimeKind);
}

export async function updateAgent(
  id: string,
  req: AgentCreateRequest,
): Promise<AgentDefinition> {
  // Definition editors update different slices independently. Materialize a
  // complete definition before PATCH so an omitted tools/skills/workspace
  // field cannot erase configuration owned by another editor.
  const current = await getAgent(id);
  const complete = {
    name: req.name || current.name,
    description: req.description ?? current.description,
    system: req.system ?? current.system,
    model: req.model ?? current.model,
    maxIters: req.maxIters ?? current.maxIters,
    tools: req.tools ?? current.tools,
    mcpServers: req.mcpServers ?? current.mcpServers,
    skills: req.skills ?? current.skills,
    workspacePath: req.workspacePath ?? current.workspacePath,
    workspaceId: req.workspaceId ?? current.workspaceId,
    workspaceBinding: req.workspaceBinding !== undefined ? req.workspaceBinding : req.workspaceId !== undefined && req.workspaceId !== current.workspaceId ? (req.workspaceId ? { version: 0, overrides: [], instructions: current.workspaceBinding?.instructions ?? current.system ?? '' } : null) : current.workspaceBinding ? {
      ...current.workspaceBinding,
      instructions: req.system !== undefined ? req.system : current.workspaceBinding.instructions,
      overrides: [...new Set([...current.workspaceBinding.overrides, ...(req.tools !== undefined ? ['tools'] : []), ...(req.mcpServers !== undefined ? ['mcpServers'] : []), ...(req.skills !== undefined ? ['skills'] : [])])],
    } : undefined,
    defaultEnvironmentId: req.defaultEnvironmentId ?? current.defaultEnvironmentId,
    defaultVaultIds: req.defaultVaultIds ?? current.defaultVaultIds,
    defaultMemoryStoreIds: req.defaultMemoryStoreIds ?? current.defaultMemoryStoreIds,
    version: req.version ?? current.version,
  };
  const res = await fetch(`/api/v1/agents/${encodeURIComponent(id)}/definition`, {
    method: 'PATCH',
    headers: authHeaders(),
    body: JSON.stringify(complete),
  });
  if (!res.ok) throw await readApiError(res, 'Failed to update agent');
  const body = await res.json() as { agent: CatalogAgent; definition: AgentDefinition };
  const bindings = await listCatalogBindings(id);
  return mergeCatalogDefinition(body.agent, body.definition, bindings);
}

export async function deleteAgent(id: string): Promise<void> {
  await archiveAgent(id);
}

export async function archiveAgent(id: string): Promise<AgentDefinition> {
  const current = await getCatalogAgent(id);
  const res = await fetch(`/api/v1/agents/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    headers: authHeaders(),
    body: JSON.stringify({ status: 'archived', version: current.version }),
  });
  if (!res.ok) throw await readApiError(res, 'Failed to archive agent');
  const body = await res.json() as { agent: CatalogAgent };
  return catalogToDefinition(body.agent, '');
}

export async function listVersions(id: string): Promise<AgentVersionEntry[]> {
  const res = await fetch(`/api/v1/agents/${encodeURIComponent(id)}/versions`, { headers: authHeaders() });
  if (!res.ok) throw await readApiError(res, 'Failed to list versions');
  const body = await res.json() as { versions?: AgentVersionEntry[] };
  return body.versions ?? [];
}

export async function getVersion(id: string, version: number): Promise<AgentVersionEntry> {
  const res = await fetch(
    `/api/v1/agents/${encodeURIComponent(id)}/versions/${version}`,
    { headers: authHeaders() },
  );
  if (!res.ok) throw await readApiError(res, 'Failed to load version');
  const body = await res.json() as { version: AgentVersionEntry };
  return body.version;
}

interface CatalogAgent {
  id: string;
  agentKey: string;
  displayName: string;
  description?: string;
  ownerType?: string;
  ownerRef?: string;
  status: string;
  tenant: string;
  namespace: string;
  capabilities?: unknown;
  labels?: Record<string, unknown>;
  metadata?: Record<string, unknown>;
  version: number;
  createdAt: string;
  updatedAt: string;
  archivedAt?: string | null;
}

export interface CatalogBinding {
  id: string;
  agentId: string;
  kind: string;
  configuration?: unknown;
  priority: number;
  enabled: boolean;
  version: number;
  createdAt?: string;
  updatedAt?: string;
}

export interface CatalogAgentInstance {
  id: string;
  agentId: string;
  bindingId: string;
  backendKind?: string;
  instanceKey: string;
  health: string;
  capacity: number;
  activeSessions: number;
  generation: number;
  framework?: string;
  frameworkVersion?: string;
  sdkVersion?: string;
  capabilities?: unknown;
  labels?: Record<string, unknown>;
  routingKey?: string;
  lastSeenAt?: string;
}

export type TelemetryState = 'available' | 'partial' | 'not_reporting' | 'not_supported' | 'not_applicable';

export interface AgentDetailOverview {
  agentId: string;
  observedAt: string;
  window: string;
  lifecycle: string;
  readiness: { state: 'ready' | 'degraded' | 'unavailable' | 'unbound' | 'inactive'; mode: 'online' | 'on-demand'; reason: string; activeBindingId?: string };
  bindings: { total: number; enabled: number; dispatchable: number };
  instances: { total: number; healthy: number; unhealthy: number; capacity: number | null; activeSessions: number; availableCapacity: number | null };
  sessions: { active: number; idle: number; compressing: number; history: number; createdInWindow: number; lastActiveAt?: string; status: TelemetryState };
  usage: { totalTokens: number | null; errorCount: number | null; status: TelemetryState };
  entrypoints: { total: number; enabled: number; conversation: number; jobs: number };
  telemetry: Record<string, TelemetryState>;
}

export interface AgentRuntimeInventory {
  status: TelemetryState;
  items: Array<{
    agentId: string;
    bindingId: string;
    instanceKey: string;
    generation: number;
    reportedAt: string;
    healthy: boolean;
    activeSessions: number;
    subagents?: Array<{ name: string; description?: string; tools?: string[]; workspaceMode?: string; url?: string; invokeCount?: number; lastInvokedAt?: string }>;
    workspaces?: Array<{ path: string; mode?: string; sizeBytes?: number; ownerRef?: string }>;
  }>;
}

export interface HostedRuntimeOption {
  id: string;
  name: string;
  provider?: string;
}

export interface DiscoveredRuntimeOption extends HostedRuntimeOption {
  runtimeProfileId: string;
  runtimePoolId: string;
  version?: string;
  hostCount: number;
  capabilities?: RuntimeCapabilityDescriptor;
}

export interface RuntimeCapabilityDescriptor {
  displayName?: string;
  runtime?: string;
  instructions?: { supported: boolean; mode?: string; target?: string };
  workspace?: { supported: boolean; mode?: string; target?: string };
  skills?: { supported: boolean; mode?: string; target?: string };
  tools?: { supported: boolean; mode?: string; target?: string };
  mcp?: { supported: boolean; mode?: string; target?: string };
  model?: { supported: boolean; mode?: string; target?: string };
  resume?: boolean;
}

export interface HostedExecutionOverrides {
  reasoningEffort?: string;
  serviceTier?: string;
  providerConfiguration?: Record<string, unknown>;
  customArgs?: string[];
}

export interface HostedAgentSettings {
  agentId: string;
  bindingId: string;
  bindingVersion: number;
  runtimeProfile: {
    id: string; name: string; provider: string; runtime?: string; version: number;
    configuration?: Record<string, unknown>; requirements?: unknown;
  };
  runtimePool: { id: string; name: string; version: number };
  executionOverrides?: HostedExecutionOverrides;
  maxConcurrency: number;
  policyVersion: number;
}

export async function getHostedAgentSettings(agentId: string): Promise<HostedAgentSettings> {
  const res = await fetch(`/api/v1/agents/${encodeURIComponent(agentId)}/hosted-settings`, { headers: authHeaders() });
  if (!res.ok) throw await readApiError(res, 'Failed to load Hosted Agent settings');
  return ((await res.json()) as { settings: HostedAgentSettings }).settings;
}

export async function updateHostedAgentSettings(
  agentId: string,
  request: {
    runtimeProfileId: string;
    runtimePoolId: string;
    executionOverrides: HostedExecutionOverrides;
    maxConcurrency: number;
    bindingVersion: number;
    policyVersion: number;
  },
): Promise<HostedAgentSettings> {
  const res = await fetch(`/api/v1/agents/${encodeURIComponent(agentId)}/hosted-settings`, {
    method: 'PATCH', headers: authHeaders(), body: JSON.stringify(request),
  });
  if (!res.ok) throw await readApiError(res, 'Failed to update Hosted Agent settings');
  return ((await res.json()) as { settings: HostedAgentSettings }).settings;
}

async function getCatalogAgent(id: string): Promise<CatalogAgent> {
  const res = await fetch(`/api/v1/agents/${encodeURIComponent(id)}`, { headers: authHeaders() });
  if (!res.ok) throw await readApiError(res, 'Failed to load Agent identity');
  return ((await res.json()) as { agent: CatalogAgent }).agent;
}

export async function listCatalogBindings(id: string): Promise<CatalogBinding[]> {
  const res = await fetch(`/api/v1/agents/${encodeURIComponent(id)}/bindings?includeDisabled=true`, { headers: authHeaders() });
  if (!res.ok) throw await readApiError(res, 'Failed to load Agent bindings');
  return ((await res.json()) as { items?: CatalogBinding[] }).items ?? [];
}

export async function listCatalogAgentInstances(id: string): Promise<CatalogAgentInstance[]> {
  const res = await fetch(`/api/v1/agents/${encodeURIComponent(id)}/instances`, { headers: authHeaders() });
  if (!res.ok) throw await readApiError(res, 'Failed to load Agent instances');
  return ((await res.json()) as { items?: CatalogAgentInstance[] }).items ?? [];
}

export async function getAgentDetailOverview(id: string): Promise<AgentDetailOverview> {
  const res = await fetch(`/api/v1/agents/${encodeURIComponent(id)}/overview?window=24h`, { headers: authHeaders() });
  if (!res.ok) throw await readApiError(res, 'Failed to load Agent runtime overview');
  return res.json();
}

export async function getAgentRuntimeInventory(id: string): Promise<AgentRuntimeInventory> {
  const res = await fetch(`/api/v1/agents/${encodeURIComponent(id)}/runtime-inventory`, { headers: authHeaders() });
  if (!res.ok) throw await readApiError(res, 'Failed to load Agent runtime inventory');
  return res.json();
}

export async function listHostedRuntimeOptions(tenant = 'default', namespace = 'default'): Promise<{
  runtimes: DiscoveredRuntimeOption[];
  profiles: HostedRuntimeOption[];
  pools: HostedRuntimeOption[];
}> {
  const params = new URLSearchParams({ tenant, namespace });
  const res = await fetch(`/api/v1/agents/runtime-options?${params}`, { headers: authHeaders() });
  if (!res.ok) throw await readApiError(res, 'Failed to load Hosted runtime options');
  return res.json();
}

export async function setCatalogBindingEnabled(
  agentId: string,
  binding: CatalogBinding,
  enabled: boolean,
): Promise<CatalogBinding> {
  const res = await fetch(
    `/api/v1/agents/${encodeURIComponent(agentId)}/bindings/${encodeURIComponent(binding.id)}`,
    {
      method: 'PATCH',
      headers: authHeaders(),
      body: JSON.stringify({
        configuration: binding.configuration,
        priority: binding.priority,
        enabled,
        version: binding.version,
      }),
    },
  );
  if (!res.ok) throw await readApiError(res, `Failed to ${enabled ? 'enable' : 'disable'} binding`);
  return ((await res.json()) as { binding: CatalogBinding }).binding;
}

export async function rotateAgentRegistrationCredential(agentId: string, ttlSeconds?: number): Promise<string> {
  const res = await fetch(
    `/api/v1/agent-registrations/${encodeURIComponent(agentId)}/credentials/rotate`,
    {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify({ ttlSeconds }),
    },
  );
  if (!res.ok) throw await readApiError(res, 'Failed to rotate registration credential');
  return ((await res.json()) as { registrationCredential: string }).registrationCredential;
}

function millis(value?: string | null): number | null {
  if (!value) return null;
  const parsed = Date.parse(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function catalogToDefinition(agent: CatalogAgent, runtimeKind: string): AgentDefinition {
  return {
    id: agent.id,
    name: agent.displayName || agent.agentKey,
    description: agent.description,
    scope: 'user',
    ownerId: agent.ownerRef,
    createdAt: millis(agent.createdAt) ?? 0,
    updatedAt: millis(agent.updatedAt) ?? 0,
    archivedAt: millis(agent.archivedAt),
    tierForCurrentUser: 'EDIT',
    agentKey: agent.agentKey,
    status: agent.status,
    runtimeKind,
    catalogVersion: agent.version,
    tenant: agent.tenant,
    namespace: agent.namespace,
    catalogCapabilities: agent.capabilities,
    catalogLabels: agent.labels,
    catalogMetadata: agent.metadata,
  };
}

function mergeCatalogDefinition(agent: CatalogAgent, definition: AgentDefinition, bindings: CatalogBinding[]): AgentDefinition {
  return {
    ...catalogToDefinition(agent, bindings.find(binding => binding.enabled)?.kind ?? bindings[0]?.kind ?? 'managed'),
    ...definition,
    id: agent.id,
    name: definition.name || agent.displayName || agent.agentKey,
    description: definition.description ?? agent.description,
    agentKey: agent.agentKey,
    status: agent.status,
    catalogVersion: agent.version,
    runtimeKind: bindings.find(binding => binding.enabled)?.kind ?? bindings[0]?.kind ?? 'managed',
  };
}
