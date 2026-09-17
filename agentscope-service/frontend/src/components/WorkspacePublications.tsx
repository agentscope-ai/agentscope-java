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

import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { useControlPlaneScope } from '@/app/ScopeContext';
import { api } from '@/lib/apiClient';
import { publishWorkspace, workspaceRevisions } from '@/api/workspaces';
import { Button } from '@/components/ui/button';

export default function WorkspacePublications({ id }: { id: string }) {
  const scope = useControlPlaneScope();
  const [busy, setBusy] = useState(false); const [error, setError] = useState('');
  const revisions = useQuery({ queryKey: ['workspace-revisions', id], queryFn: () => workspaceRevisions(id) });
  const agents = useQuery({ queryKey: ['workspace-agents', id], queryFn: () => api.get<{ items: Array<{ id: string; name: string; version: number }> }>(`/api/workspaces/${id}/agents`) });
  async function publish() { setBusy(true); setError(''); try { await publishWorkspace(id); await revisions.refetch(); } catch (e) { setError(String(e)); } finally { setBusy(false); } }
  return <section className="mb-5 space-y-3 rounded-xl border border-border p-4"><div className="flex flex-wrap items-start justify-between gap-4"><div><h2 className="font-semibold">Draft & published revisions</h2><p className="mt-1 text-sm text-muted-foreground">Save draft changes below, then publish. Linked Agents keep their selected revision until you update their binding.</p></div>{scope.roles.some(r => ['admin', 'developer'].includes(r)) && <Button disabled={busy} onClick={() => void publish()}>{busy ? 'Publishing…' : 'Publish revision'}</Button>}</div>{(error || revisions.error || agents.error) && <p role="alert" className="text-sm text-red-600">{error || String(revisions.error || agents.error)}</p>}<div className="flex flex-wrap gap-2">{revisions.data?.map(r => <span key={r.version} className="rounded-md bg-slate-100 px-2 py-1 text-xs" title={r.digest}>v{r.version} · {r.digest.slice(0, 10)}</span>)}{revisions.data?.length === 0 && <span className="text-sm text-muted-foreground">No published revision yet.</span>}</div><div className="text-sm">Linked Agents: {agents.data?.items.length ? agents.data.items.map((a, i) => <span key={a.id}>{i > 0 && ', '}<Link className="text-primary" to={scope.scopedPath(`/agent-center/agents/${a.id}/definition/workspace`)}>{a.name}</Link></span>) : 'None'}</div></section>;
}
