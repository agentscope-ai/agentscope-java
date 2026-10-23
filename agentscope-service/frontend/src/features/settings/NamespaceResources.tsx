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

import { useControlPlaneScope } from '@/app/ScopeContext';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getAccessRequests, listResources, resourceURL, reviewAccessRequest } from '@/api/resourceAccess';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { ErrorNotice } from './AccessComponents';

export function NamespaceResources({ namespace }: { namespace: string }) {
  const [search, setSearch] = useState('');
  const query = useQuery({ queryKey: ['resource-catalog', namespace], queryFn: () => listResources(namespace) });
  const items = query.data?.items.filter(({ resource: r }) => `${r.name} ${r.kind}`.toLowerCase().includes(search.toLowerCase())) || [];
  return <section className="space-y-5">
    <div><h2 className="text-lg font-semibold">Resources & access</h2><p className="mt-1 text-sm text-slate-500">Review resource permissions and dependencies. Running a resource does not grant access to its credentials or private work.</p></div>
    <Input aria-label="Search resources" placeholder="Search resources by name or type" className="max-w-md" value={search} onChange={e => setSearch(e.target.value)} />
    <ErrorNotice error={query.error} />
    {query.isLoading && <p className="text-sm text-slate-500">Loading resources…</p>}
    <div className="grid gap-3 lg:grid-cols-2">{items.map(({ resource: r, actions }) => <Link key={`${r.kind}:${r.id}`} to={resourceURL(namespace, r.kind, r.id)} className="space-y-3 rounded-xl border p-5 hover:border-indigo-300">
      <div className="flex items-center justify-between gap-3"><h3 className="font-semibold">{r.name}</h3><Badge>{r.kind}</Badge></div>
      <div className="flex flex-wrap gap-2">{actions.map(a => <Badge key={a}>{a}</Badge>)}</div>
      {r.dependencies && <p className="text-xs text-slate-500">{r.dependencies.length} dependencies</p>}
      <p className="text-sm text-indigo-600">View access & dependencies →</p>
    </Link>)}</div>
    {!query.isLoading && !query.error && items.length === 0 && <p className="rounded-xl border border-dashed p-6 text-sm text-slate-500">No accessible resources match your search.</p>}
  </section>;
}
export function AccessRequests({ namespace }: { namespace: string }) {
  const qc = useQueryClient(); const scope = useControlPlaneScope();
  const query = useQuery({ queryKey: ['access-requests', namespace], queryFn: () => getAccessRequests(namespace) });
  const review = useMutation({ mutationFn: ({ id, approve }: { id: string; approve: boolean }) => reviewAccessRequest(namespace, id, query.data!.version, approve), onSuccess: () => { scope.refreshNamespaces(); void qc.invalidateQueries({ queryKey: ['namespace-detail'] }); void qc.invalidateQueries({ queryKey: ['resource-catalog'] }); void qc.invalidateQueries({ queryKey: ['access-requests', namespace] }); void qc.invalidateQueries({ queryKey: ['resource-access'] }); void qc.invalidateQueries({ queryKey: ['namespace-audit'] }); } });
  return <section className="space-y-4"><h2 className="text-lg font-semibold">Access requests</h2><p className="text-sm text-slate-500">Request access from a resource's permissions page. Namespace managers review requests here.</p><ErrorNotice error={query.error || review.error} />
    {query.isLoading && <p>Loading requests…</p>}
    {query.data?.items.map(r => <article key={r.id} className="space-y-3 rounded-xl border p-5"><div className="flex flex-wrap items-center justify-between gap-3"><p className="text-sm font-medium">{r.user} requests {r.action}</p><Badge>{r.status}</Badge></div><p className="break-all text-xs text-slate-500">{r.resource} · {new Date(r.createdAt).toLocaleString()}</p><p className="text-sm">{r.reason}</p>{query.data?.canManage && r.status === 'pending' && <div className="flex gap-2"><Button disabled={review.isPending} onClick={() => review.mutate({ id: r.id, approve: true })}>Approve access</Button><Button variant="outline" disabled={review.isPending} onClick={() => review.mutate({ id: r.id, approve: false })}>Deny</Button></div>}</article>)}
    {query.data?.items.length === 0 && <p className="text-sm text-slate-500">No access requests.</p>}
  </section>;
}
