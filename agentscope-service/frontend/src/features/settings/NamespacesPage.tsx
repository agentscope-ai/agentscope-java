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
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Plus, ArrowUpRight, UsersRound } from 'lucide-react';
import { createNamespace, listManagedNamespaces } from '@/api/permissions';
import { getUserId, isAdmin } from '@/api/auth';
import { useControlPlaneScope } from '@/app/ScopeContext';
import { Page, PageHeader } from '@/components/Page';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogBody } from '@/components/ui/dialog';
import { AccountPicker, ErrorNotice, MembersEditor, RoleBadges } from './AccessComponents';

export default function NamespacesPage() {
  const scope = useControlPlaneScope(); const navigate = useNavigate(); const qc = useQueryClient();
  const [params, setParams] = useSearchParams();
  const [search, setSearch] = useState(''); const [archived, setArchived] = useState(false);
  const query = useQuery({ queryKey: ['managed-namespaces'], queryFn: listManagedNamespaces });
  const items = query.data?.items.filter(n => n.archived === archived && `${n.name} ${n.displayName}`.toLowerCase().includes(search.toLowerCase())) || [];
  const creating = params.get('create') === 'true' && isAdmin();
  const close = () => { const next = new URLSearchParams(params); next.delete('create'); setParams(next, { replace: true }); };
  return <Page><PageHeader title="Namespaces" description="Organize resources and work, and choose who can access each space." actions={isAdmin() && <Button onClick={() => { const next = new URLSearchParams(params); next.set('create', 'true'); setParams(next); }}><Plus className="h-4 w-4" />Create namespace</Button>} />
    <div className="flex flex-wrap items-center gap-4"><Input className="max-w-sm" aria-label="Search namespaces" placeholder="Search namespaces" value={search} onChange={event => setSearch(event.target.value)} />{<label className="flex items-center gap-2 text-sm text-slate-600"><input type="checkbox" checked={archived} onChange={event => setArchived(event.target.checked)} />Archived</label>}</div>
    <ErrorNotice error={query.error} />
    {query.isLoading ? <p className="text-sm text-slate-500">Loading namespaces…</p> : <div className="grid gap-4 lg:grid-cols-2">{items.map(n => <article key={`${n.tenant}/${n.name}`} className="flex flex-col gap-4 rounded-xl border p-5"><div className="flex items-start justify-between gap-3"><div><Link className="text-lg font-semibold text-slate-900 hover:text-indigo-600" to={`/settings/namespaces/${encodeURIComponent(n.name)}`}>{n.displayName}</Link><p className="mt-1 text-xs text-slate-500">{n.name}</p></div><Badge>{n.archived ? 'Archived' : n.kind === 'personal' ? 'Personal' : 'Shared'}</Badge></div><div className="flex items-center gap-2 text-sm text-slate-500"><UsersRound className="h-4 w-4" />{n.memberCount} {n.memberCount === 1 ? 'member' : 'members'}</div><RoleBadges roles={n.roles || []} /><div className="mt-auto flex flex-wrap gap-2"><Button variant="outline" asChild><Link to={`/settings/namespaces/${encodeURIComponent(n.name)}`}>{n.canManage ? 'Manage namespace' : 'View access'}</Link></Button>{!n.archived && n.roles?.length > 0 && <Button variant="ghost" onClick={() => { scope.setScope(n.tenant, n.name); navigate(`/work/overview?tenant=${encodeURIComponent(n.tenant)}&namespace=${encodeURIComponent(n.name)}`); }}>Open space<ArrowUpRight className="h-4 w-4" /></Button>}</div></article>)}</div>}
    {!query.isLoading && !query.error && items.length === 0 && <p className="rounded-xl border border-dashed p-10 text-center text-sm text-slate-500">No matching namespaces.</p>}
    <Dialog open={creating} onOpenChange={open => { if (!open) close(); }}><DialogContent size="lg"><DialogHeader><DialogTitle>Create namespace</DialogTitle><DialogDescription>Create a shared space with an owner and initial members.</DialogDescription></DialogHeader><DialogBody>{creating && <CreateNamespaceForm onCreated={name => { void qc.invalidateQueries({ queryKey: ['managed-namespaces'] }); scope.refreshNamespaces(); navigate(`/settings/namespaces/${encodeURIComponent(name)}`); }} />}</DialogBody></DialogContent></Dialog>
  </Page>;
}

function CreateNamespaceForm({ onCreated }: { onCreated: (name: string) => void }) {
  const [name, setName] = useState(''); const [displayName, setDisplayName] = useState(''); const [owner, setOwner] = useState(getUserId()); const [members, setMembers] = useState<Record<string, string[]>>({});
  const create = useMutation({ mutationFn: () => createNamespace(name.trim(), displayName.trim(), owner, members), onSuccess: result => onCreated(result.namespace.name) });
  return <form className="space-y-5" onSubmit={event => { event.preventDefault(); create.mutate(); }}>
    <label className="block space-y-2 text-sm font-medium">Display name<Input value={displayName} onChange={event => setDisplayName(event.target.value)} placeholder="Engineering" maxLength={200} required /></label>
    <label className="block space-y-2 text-sm font-medium">Namespace name<Input aria-label="Namespace name" value={name} onChange={event => setName(event.target.value)} placeholder="engineering" pattern="[a-z0-9][a-z0-9-]{0,62}" required /><span className="block text-xs font-normal text-slate-500">Stable identifier. Use lowercase letters, numbers and hyphens.</span></label>
    <div className="space-y-2"><p className="text-sm font-medium">Owner</p><AccountPicker value={owner} onChange={setOwner} label="Find namespace owner" /></div>
    <div className="space-y-3"><p className="text-sm font-medium">Initial members</p><MembersEditor owner={owner} members={members} onChange={setMembers} /></div>
    <ErrorNotice error={create.error} /><Button type="submit" disabled={!name.trim() || !displayName.trim() || !owner || create.isPending || Object.values(members).some(r => !r.length)}>{create.isPending ? 'Creating…' : 'Create namespace'}</Button>
  </form>;
}
