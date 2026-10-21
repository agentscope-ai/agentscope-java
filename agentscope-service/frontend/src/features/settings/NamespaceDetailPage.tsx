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
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getNamespace, listManagedNamespaces, setNamespaceArchived, transferNamespace, updateNamespace, type Namespace } from '@/api/permissions';
import { getUserId, isAdmin } from '@/api/auth';
import { useControlPlaneScope } from '@/app/ScopeContext';
import { Page, PageHeader } from '@/components/Page';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogBody } from '@/components/ui/dialog';
import { AccountPicker, ErrorNotice, MembersEditor, RoleBadges, RoleGuide } from './AccessComponents';
import { NamespaceAuditList } from './AccessLogPage';
import { NamespaceResources, AccessRequests } from './NamespaceResources';
import { NamespaceGroups } from './NamespaceGroups';
import { SharedTemplates } from './SharedTemplates';

export default function NamespaceDetailPage() {
  const { namespaceName = '' } = useParams(); const scope = useControlPlaneScope(); const qc = useQueryClient();
  const list = useQuery({ queryKey: ['managed-namespaces'], queryFn: listManagedNamespaces });
  const summary = list.data?.items.find(n => n.name === namespaceName);
  const query = useQuery({ queryKey: ['namespace-detail', namespaceName], queryFn: () => getNamespace(namespaceName), enabled: !!summary?.canManage });
  const [searchParams, setSearchParams] = useSearchParams();
  const [draft, setDraft] = useState<Namespace>(); const tab = searchParams.get('tab') || (summary?.canManage ? 'members' : 'access');
  const setTab = (value: string) => { const next = new URLSearchParams(searchParams); next.set('tab', value); setSearchParams(next); };
  const [dialog, setDialog] = useState<'transfer' | 'archive' | null>(null); const [newOwner, setNewOwner] = useState('');
  const [notice, setNotice] = useState('');
  useEffect(() => { setDraft(query.data?.namespace); }, [query.data]);
  const refresh = async () => { await qc.invalidateQueries({ queryKey: ['namespace-detail', namespaceName] }); await qc.invalidateQueries({ queryKey: ['managed-namespaces'] }); await qc.invalidateQueries({ queryKey: ['namespace-audit'] }); await qc.invalidateQueries({ queryKey: ['account-namespaces'] }); scope.refreshNamespaces(); };
  const save = useMutation({ mutationFn: () => updateNamespace(draft!), onSuccess: async () => { setNotice('Namespace saved.'); await refresh(); } });
  const transfer = useMutation({ mutationFn: () => transferNamespace(query.data!.namespace, newOwner), onSuccess: async () => { setDialog(null); setNewOwner(''); setNotice('Ownership transferred. The previous owner remains a namespace administrator.'); await refresh(); } });
  const archive = useMutation({ mutationFn: () => setNamespaceArchived(query.data!.namespace, !query.data!.namespace.archived), onSuccess: async () => { setDialog(null); setNotice('Namespace status updated.'); await refresh(); } });
  const canOwn = !!draft && (draft.owner === getUserId() || isAdmin());
  const tabs = summary?.canManage ? ['members', 'resources', ...(summary.kind === 'shared' ? ['user groups'] : []), 'requests', 'shared templates', 'settings', 'access log'] : ['access', 'resources', ...(summary?.kind === 'shared' ? ['user groups'] : []), 'requests', 'shared templates'];
  return <Page><Link className="text-sm text-slate-500 hover:text-indigo-600" to="/settings/namespaces">← Namespaces</Link><PageHeader title={summary?.displayName || namespaceName} description={<span>{namespaceName} · {summary?.kind === 'personal' ? 'Personal space' : summary?.kind === 'global' ? 'Global space' : 'Shared space'}</span>} actions={summary?.archived ? <Badge>Archived</Badge> : <RoleBadges roles={summary?.roles} />} />
    <ErrorNotice error={list.error || query.error || save.error} />
    {notice && <p role="status" className="rounded-lg bg-emerald-50 p-3 text-sm text-emerald-700">{notice}</p>}
    {list.isLoading || query.isLoading && summary?.canManage ? <p className="text-sm text-slate-500">Loading namespace…</p> : !summary ? <p className="rounded-xl border p-6 text-sm text-slate-500">This namespace is unavailable or you no longer have access.</p> : <>
      <nav aria-label="Namespace settings" className="flex gap-5 overflow-x-auto border-b">{tabs.map(t => <button type="button" key={t} onClick={() => setTab(t)} className={`whitespace-nowrap border-b-2 pb-3 text-sm font-medium capitalize ${tab === t || tabs.length === 1 ? 'border-indigo-600 text-indigo-700' : 'border-transparent text-slate-500'}`}>{t}</button>)}</nav>
      {!summary.canManage && (tab === "access" || tab === "members") && <section className="space-y-4 rounded-xl border p-5"><h2 className="font-semibold">Your namespace access</h2><RoleBadges roles={summary.roles} /><p className="text-sm text-slate-500">{summary.kind === 'global' ? 'This global namespace is available to every account and managed by the platform.' : `Contact the namespace owner (${summary.owner}) to change your access.`} Individual work may have additional sharing rules.</p><RoleGuide /></section>}
      {summary.canManage && draft && tab === 'members' && <section className="space-y-5"><div><h2 className="text-lg font-semibold">Members & permissions</h2><p className="mt-1 text-sm text-slate-500">Membership controls this namespace. Platform roles are managed separately in Users.</p></div>{draft.kind === 'personal' ? <p className="rounded-xl border p-5 text-sm text-slate-500">Personal namespace membership is fixed to its owner.</p> : <><MembersEditor key={namespaceName} namespace={namespaceName} owner={draft.owner} members={draft.members || {}} onChange={members => { setNotice(''); setDraft({ ...draft, members }); }} /><Button disabled={save.isPending || Object.values(draft.members || {}).some(r => !r.length)} onClick={() => save.mutate()}>{save.isPending ? 'Saving…' : 'Save membership'}</Button></>}<details className="rounded-xl border p-5"><summary className="cursor-pointer text-sm font-medium">Role permissions</summary><div className="mt-4"><RoleGuide /></div></details></section>}
      {summary.canManage && draft && tab === 'settings' && <div className="max-w-3xl space-y-5"><section className="space-y-4 rounded-xl border p-5"><h2 className="font-semibold">General</h2><label className="block space-y-2 text-sm">Display name<Input value={draft.displayName} maxLength={200} onChange={e => setDraft({ ...draft, displayName: e.target.value })} /></label><p className="text-xs text-slate-500">The namespace identifier remains {draft.name}.</p><Button disabled={!draft.displayName.trim() || save.isPending} onClick={() => save.mutate()}>Save settings</Button></section>{draft.kind === 'shared' && canOwn && <><section className="space-y-3 rounded-xl border p-5"><h2 className="font-semibold">Ownership</h2><p className="text-sm text-slate-500">Transfer responsibility to another active account. The previous owner retains administrator access.</p><Button variant="outline" disabled={draft.archived} onClick={() => setDialog('transfer')}>Transfer ownership</Button></section><section className="space-y-3 rounded-xl border p-5"><h2 className="font-semibold">{draft.archived ? 'Restore namespace' : 'Archive namespace'}</h2><p className="text-sm text-slate-500">Archiving preserves resources and history while removing member access. Restore it to make access available again.</p><Button variant="outline" onClick={() => setDialog('archive')}>{draft.archived ? 'Restore namespace' : 'Archive namespace'}</Button></section></>}</div>}
      {tab === 'resources' && <NamespaceResources namespace={namespaceName} />}
      {tab === 'user groups' && <NamespaceGroups namespace={namespaceName} />}
      {tab === 'requests' && <AccessRequests namespace={namespaceName} />}
      {tab === 'shared templates' && <SharedTemplates namespace={namespaceName} />}
      {summary.canManage && tab === 'access log' && <NamespaceAuditList name={namespaceName} />}
    </>}
    <Dialog open={dialog !== null} onOpenChange={open => { if (!open) setDialog(null); }}><DialogContent size="md"><DialogHeader><DialogTitle>{dialog === 'transfer' ? 'Transfer namespace ownership' : draft?.archived ? 'Restore namespace' : 'Archive namespace'}</DialogTitle><DialogDescription>{dialog === 'transfer' ? 'Select the account that will own this namespace.' : `This changes member access to ${summary?.displayName || namespaceName}. Resources and history are retained.`}</DialogDescription></DialogHeader><DialogBody><div className="space-y-4">{dialog === 'transfer' && <AccountPicker namespace={namespaceName} value={newOwner} onChange={setNewOwner} exclude={[draft?.owner || '']} label="New namespace owner" />}<ErrorNotice error={transfer.error || archive.error} /><Button disabled={dialog === 'transfer' ? !newOwner || transfer.isPending : archive.isPending} onClick={() => dialog === 'transfer' ? transfer.mutate() : archive.mutate()}>{dialog === 'transfer' ? 'Transfer ownership' : draft?.archived ? 'Restore namespace' : 'Archive namespace'}</Button></div></DialogBody></DialogContent></Dialog>
  </Page>;
}
