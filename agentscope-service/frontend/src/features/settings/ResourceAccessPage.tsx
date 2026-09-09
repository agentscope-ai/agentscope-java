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
import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { getResourceAccess, listResources, resourceActions, resourceURL, requestResourceAccess, saveResourceAccess, type ResourcePolicy } from '@/api/resourceAccess';
import { searchAccounts, listManagedNamespaces } from '@/api/permissions';
import { Page, PageHeader } from '@/components/Page';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { ErrorNotice, AccountPicker, accountLabel } from './AccessComponents';

const descriptions: Record<string, string> = { discover: 'Find this resource', use: 'Run or attach this resource', inspect: 'Read configuration or content', edit: 'Change configuration or content', publish: 'Publish versions or share templates', manage: 'Change resource permissions' };
export default function ResourceAccessPage() {
  const { namespaceName = '', kind = '', resourceId = '' } = useParams(); const qc = useQueryClient(); const scope = useControlPlaneScope();
  const query = useQuery({ queryKey: ['resource-access', namespaceName, kind, resourceId], queryFn: () => getResourceAccess(namespaceName, kind, resourceId) });
  const [policy, setPolicy] = useState<ResourcePolicy>(); const [user, setUser] = useState(''); const [group, setGroup] = useState(''); const [notice, setNotice] = useState('');
  const [action, setAction] = useState('use'); const [reason, setReason] = useState('');
  useEffect(() => { setPolicy(query.data?.policy); }, [query.data]);
  const catalog = useQuery({ queryKey: ['resource-catalog', namespaceName], queryFn: () => listResources(namespaceName) });
  const spaces = useQuery({ queryKey: ['managed-namespaces'], queryFn: listManagedNamespaces, enabled: !!query.data?.canManage && kind === 'workflow' });
  const ids = Object.keys(policy?.users || {});
  const accounts = useQuery({ queryKey: ['resource-accounts', namespaceName, ids], queryFn: () => searchAccounts(namespaceName, '', ids), enabled: ids.length > 0 && !!query.data?.canManage });
  const save = useMutation({ mutationFn: () => saveResourceAccess(namespaceName, kind, resourceId, query.data!.version, policy!), onSuccess: () => { setNotice('Resource permissions saved.'); scope.refreshNamespaces(); void qc.invalidateQueries({ queryKey: ['namespace-detail'] }); void qc.invalidateQueries({ queryKey: ['resource-access'] }); void qc.invalidateQueries({ queryKey: ['resource-catalog'] }); void qc.invalidateQueries({ queryKey: ['namespace-audit'] }); } });
  const request = useMutation({ mutationFn: () => requestResourceAccess(namespaceName, query.data!.version, `${kind}:${resourceId}`, action, reason), onSuccess: () => { setReason(''); setNotice('Access request submitted.'); void qc.invalidateQueries({ queryKey: ['access-requests'] }); void qc.invalidateQueries({ queryKey: ['resource-access'] }); } });
  const resource = query.data?.resource;
  const nameFor = (key: string) => catalog.data?.items.find(x => `${x.resource.kind}:${x.resource.id}` === key)?.resource.name || key;
  const toggle = (section: 'users' | 'groups', id: string, action: string, checked: boolean) => { if (!policy) return; const current = policy[section]?.[id] || []; setPolicy({ ...policy, [section]: { ...policy[section], [id]: checked ? [...current, action] : current.filter(a => a !== action) } }); };
  return <Page><Link className="text-sm text-indigo-600" to={`/settings/namespaces/${encodeURIComponent(namespaceName)}?tab=resources`}>← Namespace resources</Link><PageHeader title={resource?.name || 'Resource access'} description={`${namespaceName} · ${kind}`} />
    <ErrorNotice error={query.error || save.error || request.error} />{notice && <p role="status" className="rounded-lg bg-emerald-50 p-3 text-sm text-emerald-700">{notice}</p>}
    {query.isLoading && <p>Loading permissions…</p>}
    {query.data && <>
      <section className="space-y-4 rounded-xl border p-5"><h2 className="font-semibold">Your effective permissions</h2><div className="grid gap-3 md:grid-cols-2">{query.data.decisions.map(d => <div key={d.action} className="rounded-lg bg-slate-50 p-3"><p className="text-sm font-medium"><span className={d.allowed ? 'text-emerald-700' : 'text-slate-500'}>{d.allowed ? 'Allowed' : 'Not granted'}</span> · {d.action}</p><p className="mt-1 text-xs text-slate-500">{d.reason}</p></div>)}</div></section>
      <ErrorNotice error={query.data.dependencyError} />
      {resource?.dependencies && <section className="space-y-3 rounded-xl border p-5"><h2 className="font-semibold">Dependencies</h2><p className="text-sm text-slate-500">A dependency requires your own use permission or approval for this resource to use it on your behalf.</p>{resource.dependencies.map(key => { const [k, ...parts] = key.split(':'); return <Link key={key} className="block text-sm text-indigo-600" to={resourceURL(namespaceName, k, parts.join(':'))}>{nameFor(key)} <span className="text-slate-400">{k}</span></Link>; })}{resource.dependencies.length === 0 && <p className="text-sm text-slate-500">No configured dependencies.</p>}</section>}
      {query.data.canManage && policy && <section className="space-y-5 rounded-xl border p-5"><h2 className="font-semibold">Resource grants</h2>
        <label className="block space-y-2 text-sm">Access policy<select aria-label="Access policy" className="block h-10 w-full max-w-md rounded-lg border px-3" value={policy.mode} onChange={e => setPolicy({ ...policy, mode: e.target.value as ResourcePolicy['mode'] })}><option value="inherit">Inherit namespace roles, plus explicit grants</option><option value="restricted">Only explicit resource grants</option></select></label>
        <p className="text-xs text-slate-500">Namespace membership is always required. Namespace administrators retain permission to manage grants. Resource grants do not change private Issue sharing.</p>
        {(['users', 'groups'] as const).map(section => <div key={section} className="space-y-3"><h3 className="text-sm font-semibold capitalize">{section}</h3>{Object.entries(policy[section] || {}).map(([id, actions]) => <div key={id} className="space-y-3 rounded-lg border p-4"><div className="flex items-center justify-between gap-2"><span className="text-sm font-medium">{section === 'groups' ? query.data?.groups?.[id]?.name || id : (() => { const a = accounts.data?.items.find(a => a.userId === id); return a ? accountLabel(a) : id; })()}</span><Button type="button" variant="ghost" size="sm" onClick={() => { const next = { ...policy[section] }; delete next[id]; setPolicy({ ...policy, [section]: next }); }}>Remove grant</Button></div><div className="flex flex-wrap gap-4">{resourceActions.map(a => <label key={a} title={descriptions[a]} className="flex items-center gap-2 text-xs"><input aria-label={`${id} ${a}`} type="checkbox" checked={actions.includes(a)} onChange={e => toggle(section, id, a, e.target.checked)} />{a}</label>)}</div></div>)}</div>)}
        <div className="grid gap-3 md:grid-cols-2"><div className="space-y-2"><AccountPicker namespace={namespaceName} value={user} onChange={setUser} label="Grant access to a member" exclude={ids} /><Button type="button" variant="outline" disabled={!user} onClick={() => { setPolicy({ ...policy, users: { ...policy.users, [user]: ['discover', 'use'] } }); setUser(''); }}>Add user grant</Button></div><div className="space-y-2"><select aria-label="Grant access to a group" className="h-10 w-full rounded-lg border px-3 text-sm" value={group} onChange={e => setGroup(e.target.value)}><option value="">Choose a user group</option>{Object.entries(query.data.groups || {}).filter(([id]) => !policy.groups?.[id]).map(([id, g]) => <option key={id} value={id}>{g.name}</option>)}</select><Button type="button" variant="outline" disabled={!group} onClick={() => { setPolicy({ ...policy, groups: { ...policy.groups, [group]: ['discover', 'use'] } }); setGroup(''); }}>Add group grant</Button></div></div>
        <div className="space-y-3 border-t pt-4"><h3 className="font-semibold">Resources allowed to use this dependency</h3><p className="text-sm text-slate-500">Approval applies only through the selected resource. Its callers cannot read this dependency's configuration or credentials.</p>{query.data.dependents?.map(r => { const key = `${r.kind}:${r.id}`; return <label key={key} className="flex items-center gap-2 text-sm"><input type="checkbox" aria-label={`Allow ${r.name} as consumer`} checked={policy.consumers?.includes(key) || false} onChange={e => setPolicy({ ...policy, consumers: e.target.checked ? [...policy.consumers || [], key] : policy.consumers?.filter(k => k !== key) })} />{r.name}<Badge>{r.kind}</Badge></label>; })}{query.data.dependents?.length === 0 && <p className="text-sm text-slate-500">No resources currently reference this dependency.</p>}</div>
        {kind === 'workflow' && <div className="space-y-3 border-t pt-4"><h3 className="font-semibold">Share published Workflow templates</h3><p className="text-sm text-slate-500">Selected namespaces can import published revisions. They choose their own execution resources; runtime credentials and bindings are not copied.</p>{spaces.data?.items.filter(n => n.name !== namespaceName && !n.archived).map(n => <label key={n.name} className="flex items-center gap-2 text-sm"><input aria-label={`Share template with ${n.displayName}`} type="checkbox" checked={policy.exportTo?.includes(n.name) || false} onChange={e => setPolicy({ ...policy, exportTo: e.target.checked ? [...policy.exportTo || [], n.name] : policy.exportTo?.filter(id => id !== n.name) })} />{n.displayName}</label>)}<ErrorNotice error={spaces.error} /></div>}
        <Button disabled={save.isPending || [...Object.values(policy.users || {}), ...Object.values(policy.groups || {})].some(a => !a.length)} onClick={() => save.mutate()}>Save resource permissions</Button>
      </section>}
      <section className="space-y-4 rounded-xl border p-5"><h2 className="font-semibold">Request additional access</h2><select aria-label="Requested permission" className="h-10 rounded-lg border px-3 text-sm" value={action} onChange={e => setAction(e.target.value)}>{resourceActions.map(a => <option key={a} value={a}>{a} — {descriptions[a]}</option>)}</select><Input aria-label="Access request reason" placeholder="Explain what you need this resource for" value={reason} maxLength={1000} onChange={e => setReason(e.target.value)} /><Button disabled={reason.trim().length < 3 || request.isPending} onClick={() => request.mutate()}>Request access</Button></section>
    </>}
  </Page>;
}
