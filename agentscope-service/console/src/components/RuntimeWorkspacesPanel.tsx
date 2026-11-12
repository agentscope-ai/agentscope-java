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

import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { getAgentRuntimeInventory, type AgentDefinition } from '@/api/agents';
import { useControlPlaneScope } from '@/app/ScopeContext';

export default function RuntimeWorkspacesPanel({ agent }: { agent: AgentDefinition }) {
  const scope = useControlPlaneScope();
  const inventory = useQuery({ queryKey: ['runtime-workspaces', agent.id], queryFn: () => getAgentRuntimeInventory(agent.id), refetchInterval: 30000 });
  const items = inventory.data?.items.flatMap(instance => (instance.workspaces || []).map(workspace => ({ ...workspace, instance: instance.instanceKey, reportedAt: instance.reportedAt }))) || [];
  return <section className="space-y-3 rounded-xl border p-5"><h2 className="font-semibold">Runtime workspaces</h2><p className="text-sm text-muted-foreground">{agent.runtimeKind === 'managed' ? 'Each Session has an isolated runtime workspace containing materialized definition files, private working memory and task inputs.' : agent.runtimeKind === 'hosted-runtime' ? 'Runtime Hosts keep task or conversation workspaces. Provider state and project files stay separate from the published Agent definition.' : 'The external application owns its runtime directories and retention policy. Reports below are observations from its instances.'}</p>{inventory.isLoading && <p className="text-sm">Loading reports…</p>}{inventory.error && <p role="alert" className="text-sm text-red-600">Runtime workspace reports are unavailable.</p>}{!inventory.isLoading && !inventory.error && !items.length && <p className="rounded-lg bg-slate-50 p-3 text-sm text-muted-foreground">No directory report is available. This does not mean that the runtime workspace is empty.</p>}{items.map((item, index) => <div key={`${item.instance}/${item.path}/${index}`} className="space-y-1 rounded-lg border p-3 text-xs"><p className="font-medium">{item.instance} · {item.mode || 'runtime'}</p><p className="break-all font-mono">{item.path}</p><p className="text-muted-foreground">{item.sizeBytes == null ? 'Size not reported' : `${item.sizeBytes.toLocaleString()} bytes`} · Reported {new Date(item.reportedAt).toLocaleString()}</p></div>)}<div className="flex flex-wrap gap-4 text-sm"><Link className="text-primary" to={scope.scopedPath(`/agent-center/agents/${agent.id}?tab=activity&view=sessions`)}>Sessions, logs and execution context →</Link><Link className="text-primary" to={scope.scopedPath('/agent-center/memory')}>Shared knowledge stores →</Link></div><p className="text-xs text-muted-foreground">Access to a definition does not grant access to another user’s execution data. Runtime memory and logs are never published with a Workspace.</p></section>;
}
