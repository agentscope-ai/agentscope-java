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
import { Navigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { listNamespaceAudit } from '@/api/permissions';
import { listAccountAudit, listUsers } from '@/api/admin';
import { isAdmin } from '@/lib/auth';
import { Page, PageHeader } from '@/components/Page';
import { Button } from '@/components/ui/button';
import { ErrorNotice, RoleBadges } from './AccessComponents';

export function NamespaceAuditList({ name }: { name?: string }) {
  const [offset, setOffset] = useState(0);
  const query = useQuery({ queryKey: ['namespace-audit', name, offset], queryFn: () => listNamespaceAudit(name, offset) });
  return <div className="space-y-4"><ErrorNotice error={query.error} />{query.isLoading ? <p className="text-sm text-slate-500">Loading access history…</p> : query.data?.items.length === 0 ? <p className="rounded-xl border p-5 text-sm text-slate-500">No access changes recorded.</p> : query.data?.items.map(a => <details key={a.id} className="rounded-xl border p-4"><summary className="cursor-pointer text-sm"><span className="font-medium">{a.namespace.displayName}</span><span className="ml-3 text-slate-500">Version {a.version} · {a.actor} · {new Date(a.createdAt).toLocaleString()}</span></summary><div className="mt-4 space-y-3 text-sm"><p>Owner: {a.namespace.owner} · {a.namespace.archived ? 'Archived' : 'Active'}</p>{Object.entries(a.namespace.members || {}).map(([id, roles]) => <div key={id} className="flex flex-wrap items-center gap-3"><span className="break-all text-xs text-slate-500">{id}</span><RoleBadges roles={roles} /></div>)}{Object.keys(a.namespace.members || {}).length === 0 && Object.keys(a.namespace.groups || {}).length === 0 && <p className="text-slate-500">Owner only.</p>}{Object.entries(a.namespace.groups || {}).map(([id, g]) => <div key={id} className="space-y-2 rounded-lg bg-slate-50 p-3"><p className="font-medium">User group: {g.name} ({id})</p><RoleBadges roles={g.roles} /><p className="break-all text-xs text-slate-500">Members: {g.members?.join(', ') || 'None'}</p></div>)}{Object.entries(a.namespace.resources || {}).map(([key, p]) => <div key={key} className="space-y-1 rounded-lg bg-slate-50 p-3"><p className="break-all font-medium">Resource: {key} · {p.mode}</p>{Object.entries(p.users || {}).map(([id, actions]) => <p key={id} className="break-all text-xs">User {id}: {actions.join(', ')}</p>)}{Object.entries(p.groups || {}).map(([id, actions]) => <p key={id} className="text-xs">Group {id}: {actions.join(', ')}</p>)}{!!p.consumers?.length && <p className="break-all text-xs">Approved consumers: {p.consumers.join(', ')}</p>}{!!p.exportTo?.length && <p className="text-xs">Template exports: {p.exportTo.join(', ')}</p>}</div>)}{a.namespace.requests?.map(r => <p key={r.id} className="break-all text-xs text-slate-500">Access request: {r.user} · {r.resource} · {r.action} · {r.status}{r.reviewedBy ? ` · Reviewed by ${r.reviewedBy}` : ''}</p>)}</div></details>)}<Pagination offset={offset} count={query.data?.items.length || 0} onChange={setOffset} /></div>;
}
function Pagination({ offset, count, onChange }: { offset: number; count: number; onChange: (n: number) => void }) { return <div className="flex gap-2"><Button variant="outline" size="sm" disabled={offset === 0} onClick={() => onChange(Math.max(0, offset - 50))}>Previous</Button><Button variant="outline" size="sm" disabled={count < 50} onClick={() => onChange(offset + 50)}>Next</Button></div>; }

export default function AccessLogPage() {
  const [tab, setTab] = useState('namespaces'); const [offset, setOffset] = useState(0); const admin = isAdmin();
  const users = useQuery({ queryKey: ['admin-users'], queryFn: listUsers, enabled: admin });
  const accounts = useQuery({ queryKey: ['account-audit', offset], queryFn: () => listAccountAudit(offset), enabled: admin && tab === 'accounts' });
  const label = (id: string) => users.data?.find(a => a.userId === id)?.username || id;
  if (!admin) return <Navigate to="/settings/namespaces" replace />;
  return <Page><PageHeader title="Access log" description="Review account lifecycle, platform-role changes and namespace authorization history." /><div className="flex gap-2"><Button variant={tab === 'namespaces' ? 'default' : 'outline'} onClick={() => setTab('namespaces')}>Namespace access</Button><Button variant={tab === 'accounts' ? 'default' : 'outline'} onClick={() => setTab('accounts')}>Accounts & platform roles</Button></div>{tab === 'namespaces' ? <NamespaceAuditList /> : <div className="space-y-4"><ErrorNotice error={accounts.error} />{accounts.isLoading && <p className="text-sm text-slate-500">Loading account history…</p>}{accounts.data?.items.map(a => <article key={a.id} className="space-y-2 rounded-xl border p-4"><p className="text-sm font-medium">{label(a.userId)} · {a.action.replace(/\./g, ' ')}</p><p className="text-xs text-slate-500">By {label(a.actor)} · {new Date(a.createdAt).toLocaleString()}</p>{Array.isArray(a.details.roles) && <p className="text-sm text-slate-600">Platform roles: {(a.details.roles as string[]).join(', ')}</p>}</article>)}{accounts.data?.items.length === 0 && <p className="text-sm text-slate-500">No account changes recorded.</p>}<Pagination offset={offset} count={accounts.data?.items.length || 0} onChange={setOffset} /></div>}</Page>;
}
