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

import { useEffect, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { api } from '@/lib/apiClient';
import { useControlPlaneScope } from '@/app/ScopeContext';
import { updateAgent, type AgentDefinition, type WorkspaceBinding } from '@/api/agents';
import { listWorkspaces, workspaceRevisions } from '@/api/workspaces';
import { Button } from '@/components/ui/button';

export type WorkspaceCapabilities = { bindings: Array<{ bindingId: string; kind: string; runtime: string; status: string; capabilities: Array<{ name: string; mode: string; requested: boolean; supported: boolean; target?: string; reason?: string }> }>; applications?: Array<{ digest: string; version: number; workspaceVersion: number; appliedAt: number }> };

export default function WorkspaceBindingPanel({ agent, canEdit, onSaved }: { agent: AgentDefinition; canEdit: boolean; onSaved: () => Promise<unknown> }) {
  const scope = useControlPlaneScope();
  const [workspaceId, setWorkspaceId] = useState(agent.workspaceId || '');
  const [binding, setBinding] = useState<WorkspaceBinding>(agent.workspaceBinding || { version: 0, overrides: [], instructions: agent.system || '' });
  const [skills, setSkills] = useState(agent.skills || []);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const workspaces = useQuery({ queryKey: ['workspaces', scope.tenant, scope.namespace], queryFn: listWorkspaces });
  const revisions = useQuery({ queryKey: ['workspace-revisions', workspaceId], queryFn: () => workspaceRevisions(workspaceId), enabled: !!workspaceId });
  const capabilities = useQuery({ queryKey: ['workspace-capabilities', agent.id, agent.version], queryFn: () => api.get<WorkspaceCapabilities>(`/api/v1/agents/${agent.id}/workspace-capabilities`) });
  useEffect(() => { setWorkspaceId(agent.workspaceId || ''); setSkills(agent.skills || []); setBinding(agent.workspaceBinding || { version: 0, overrides: [], instructions: agent.system || '' }); }, [agent.id, agent.version]);
  async function save() {
    setBusy(true); setError('');
    try {
      if (agent.version == null) await api.post(`/api/v1/agents/${agent.id}/definition`, {});
      await updateAgent(agent.id, { name: agent.name, workspaceId, workspaceBinding: workspaceId ? binding : null, ...(binding.overrides.includes('skills') ? { skills } : {}), ...(workspaceId ? {} : { system: binding.instructions || '' }), version: agent.version });
      await onSaved(); await revisions.refetch(); await capabilities.refetch();
    } catch (e) { setError(e instanceof Error ? e.message : 'Unable to save Workspace binding'); }
    finally { setBusy(false); }
  }
  return <section className="space-y-4 rounded-xl border p-5">
    <div><h2 className="font-semibold">Workspace definition</h2><p className="mt-1 text-sm text-muted-foreground">Reuse published instructions, skills, tools and subagents. Each Agent version keeps its resolved definition; draft edits do not change it.</p></div>
    {agent.runtimeKind === 'external-application' && <p className="rounded-lg bg-amber-50 p-3 text-sm text-amber-900">External applications must explicitly enable the Workspace consumer before they can run a platform definition. Local application files remain application-managed.</p>}
    <div className="grid gap-4 sm:grid-cols-2">
      <label className="grid gap-1 text-sm">Workspace<select aria-label="Definition workspace" className="h-10 rounded-lg border px-3" disabled={!canEdit || workspaces.isLoading || !!workspaces.error} value={workspaceId} onChange={e => { setWorkspaceId(e.target.value); setBinding(b => ({ ...b, version: 0, digest: undefined })); }}><option value="">Agent-private definition</option>{workspaces.data?.map(w => <option key={w.id} value={w.id}>{w.name}</option>)}</select></label>
      {workspaceId && <label className="grid gap-1 text-sm">Published revision<select aria-label="Workspace revision" className="h-10 rounded-lg border px-3" disabled={!canEdit || revisions.isLoading || !!revisions.error} value={binding.version} onChange={e => setBinding(b => ({ ...b, version: Number(e.target.value) }))}><option value={0}>Publish current draft and bind</option>{revisions.data?.map(r => <option key={r.version} value={r.version}>v{r.version} · {r.digest.slice(0, 10)}</option>)}</select></label>}
    </div>
    {!!workspaceId && <>
      <Link className="text-sm text-primary" to={scope.scopedPath(`/agent-center/workspaces/${workspaceId}`)}>Open Workspace draft and versions →</Link>
      <fieldset className="space-y-2"><legend className="mb-2 text-sm font-medium">Agent overrides</legend><p className="text-xs text-muted-foreground">Unchecked fields inherit the selected revision. Checked fields keep this Agent’s configuration.</p><div className="flex flex-wrap gap-4">{(['tools', 'mcpServers', 'skills'] as const).map(field => <label key={field} className="flex items-center gap-2 text-sm"><input type="checkbox" disabled={!canEdit} checked={binding.overrides.includes(field)} onChange={e => setBinding(b => ({ ...b, overrides: e.target.checked ? [...b.overrides, field] : b.overrides.filter(f => f !== field) }))} />{field === 'mcpServers' ? 'MCP connections' : field === 'skills' ? 'Enabled skills' : 'Tools'}</label>)}</div></fieldset>
      {binding.overrides.includes('skills') && <fieldset className="space-y-2"><legend className="text-sm">Enabled skills for this Agent</legend>{Array.from(new Set([...(revisions.data?.find(r => r.version === binding.version)?.skills || []).map(s => s.name || s.id || ''), ...skills.map(s => s.name || s.id || '')])).filter(Boolean).map(name => <label key={name} className="flex items-center gap-2 text-sm"><input type="checkbox" disabled={!canEdit} checked={skills.some(s => (s.name || s.id) === name)} onChange={e => setSkills(current => e.target.checked ? [...current, { name }] : current.filter(s => (s.name || s.id) !== name))} />{name}</label>)}<p className="text-xs text-muted-foreground">Only selected skills are loaded. Select a published revision to see its available skills.</p></fieldset>}
    </>}
    <label className="grid gap-1 text-sm">Agent-specific instructions<textarea className="min-h-24 rounded-lg border p-3" disabled={!canEdit} value={binding.instructions || ''} onChange={e => setBinding(b => ({ ...b, instructions: e.target.value }))} /><span className="text-xs text-muted-foreground">Appended after the Workspace instructions. Runtime permissions still apply.</span></label>
    {agent.workspaceBinding && <p className="text-xs text-muted-foreground">Bound Workspace v{agent.workspaceBinding.version} · Agent v{agent.version} · {agent.definitionDigest?.slice(0, 12)}</p>}
    {(error || workspaces.error || revisions.error) && <p role="alert" className="text-sm text-red-600">{error || String(workspaces.error || revisions.error)}</p>}
    {canEdit && <Button disabled={busy || !!workspaces.error || (!!workspaceId && !!revisions.error)} onClick={() => void save()}>{busy ? 'Saving…' : 'Save definition binding'}</Button>}
    <div className="space-y-3 border-t pt-4"><h3 className="text-sm font-semibold">Runtime compatibility · saved definition</h3>
      {capabilities.isLoading && <p className="text-sm text-muted-foreground">Loading runtime capabilities…</p>}
      {capabilities.error && <p role="alert" className="text-sm text-red-600">Unable to load runtime compatibility.</p>}
      {capabilities.data?.bindings.map(runtime => <div key={runtime.bindingId} className="space-y-2"><p className="text-sm">{runtime.runtime} · {runtime.status.replace(/-/g, ' ')}</p><div className="grid gap-2 sm:grid-cols-3">{runtime.capabilities.map(cap => <div key={cap.name} className={`rounded-lg border p-2 text-xs ${cap.requested && !cap.supported ? 'border-amber-300 bg-amber-50' : 'bg-slate-50'}`}><div className="font-semibold capitalize">{cap.name}</div><div className="mt-1">{cap.mode.replace(/-/g, ' ')}{cap.requested ? ' · in use' : ''}</div>{cap.requested && !cap.supported && <div className="mt-1 text-amber-800">{cap.reason || 'Requires a compatible runtime before execution.'}</div>}</div>)}</div></div>)}
      {!!capabilities.data?.applications?.length && <p className="text-xs text-muted-foreground">External application last loaded Agent v{capabilities.data.applications[0].version}, Workspace v{capabilities.data.applications[0].workspaceVersion} at {new Date(capabilities.data.applications[0].appliedAt).toLocaleString()}.</p>}
    </div>
  </section>;
}
