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
import { searchAccounts, type DirectoryAccount } from '@/api/permissions';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';

export const namespaceRoles = ['viewer', 'member', 'developer', 'operator', 'admin', 'auditor'] as const;
export const roleDescriptions: Record<string, string> = {
  viewer: 'Discover resources and read work shared with you.',
  member: 'Create work and use Agents, Teams and Workflows.',
  developer: 'Configure and publish resources; create and run work.',
  operator: 'Inspect and operate namespace infrastructure.',
  admin: 'Manage members, resources and infrastructure.',
  auditor: 'Read all business work, including private Issues and execution data.',
};
export function ErrorNotice({ error }: { error: unknown }) {
  if (!error) return null;
  let message = error instanceof Error ? error.message : String(error);
  try { const body = JSON.parse(message); message = body.error || body.message || message; } catch { /* Plain text error. */ }
  return <p role="alert" className="rounded-xl border border-red-100 bg-red-50 p-3 text-sm text-red-700">{message}</p>;
}
export const accountLabel = (a: DirectoryAccount) => a.displayName ? `${a.displayName} (${a.username})` : a.username;
export function RoleBadges({ roles = [] }: { roles?: string[] }) { return <div className="flex flex-wrap gap-1.5">{roles.map(role => <Badge key={role} title={roleDescriptions[role]}>{role}</Badge>)}</div>; }
export function RoleGuide() { return <div className="grid gap-3 sm:grid-cols-2">{namespaceRoles.map(role => <div key={role} className="rounded-lg bg-slate-50 p-3"><p className="text-sm font-medium capitalize">{role}</p><p className="mt-1 text-xs leading-5 text-slate-500">{roleDescriptions[role]}</p></div>)}</div>; }

export function AccountPicker({ namespace, value, onChange, label = 'Find an account', exclude = [] }: { namespace?: string; value: string; onChange: (id: string) => void; label?: string; exclude?: string[] }) {
  const [text, setText] = useState('');
  const [query, setQuery] = useState('');
  const [open, setOpen] = useState(false);
  useEffect(() => { const timer = setTimeout(() => setQuery(text), 200); return () => clearTimeout(timer); }, [text]);
  const accounts = useQuery({ queryKey: ['account-directory', namespace, query], queryFn: () => searchAccounts(namespace, query), enabled: open });
  const selected = useQuery({ queryKey: ['account-identity', namespace, value], queryFn: () => searchAccounts(namespace, '', [value]), enabled: !!value });
  const chosen = selected.data?.items[0];
  return <div className="min-w-0 space-y-2">
    {value ? <div className="flex items-center justify-between gap-2 rounded-lg border bg-slate-50 px-3 py-2 text-sm"><span>{chosen ? accountLabel(chosen) : value}</span><Button type="button" variant="ghost" size="sm" onClick={() => { onChange(''); setOpen(true); }}>Change</Button></div> : <>
      <Input aria-label={label} role="combobox" aria-expanded={open} aria-controls={`accounts-${label.replace(/ /g, '-')}`} autoComplete="off" placeholder="Search by name or username" value={text} onFocus={() => setOpen(true)} onChange={event => { setText(event.target.value); setOpen(true); }} />
      {open && <div id={`accounts-${label.replace(/ /g, '-')}`} role="listbox" aria-label={`${label} results`} className="max-h-48 overflow-auto rounded-lg border bg-white p-1">
        {accounts.isLoading ? <p className="p-2 text-xs text-slate-500">Searching accounts…</p> : accounts.data?.items.filter(a => !a.disabled && !exclude.includes(a.userId)).map(a => <button type="button" role="option" aria-selected={false} key={a.userId} className="block w-full rounded-md px-3 py-2 text-left text-sm hover:bg-slate-50" onClick={() => { onChange(a.userId); setOpen(false); setText(''); }}>{accountLabel(a)}</button>)}
        {accounts.data && !accounts.data.items.some(a => !a.disabled && !exclude.includes(a.userId)) && <p className="p-2 text-xs text-slate-500">No matching active accounts.</p>}
        <ErrorNotice error={accounts.error} />
      </div>}
    </>}
  </div>;
}

export function MembersEditor({ namespace, owner, members, onChange }: { namespace?: string; owner: string; members: Record<string, string[]>; onChange: (members: Record<string, string[]>) => void }) {
  const [account, setAccount] = useState('');
  const [role, setRole] = useState('member');
  const ids = [...new Set([owner, ...Object.keys(members)].filter(Boolean))].sort();
  const directory = useQuery({ queryKey: ['member-identities', namespace, ids], queryFn: () => searchAccounts(namespace, '', ids), enabled: ids.length > 0 });
  const identities = new Map(directory.data?.items.map(a => [a.userId, a]));
  return <div className="space-y-5">
    <ErrorNotice error={directory.error} />
    <div className="divide-y rounded-xl border">{ids.map(id => {
      const a = identities.get(id); const isOwner = id === owner;
      return <div key={id} className="space-y-3 p-4"><div className="flex items-start justify-between gap-3"><div><p className="text-sm font-medium">{a ? accountLabel(a) : id} {isOwner && <Badge>Owner</Badge>} {a?.disabled && <Badge>Disabled</Badge>}</p><p className="mt-1 break-all text-xs text-slate-400">{id}</p></div>{!isOwner && <Button type="button" variant="ghost" size="sm" onClick={() => { const next = { ...members }; delete next[id]; onChange(next); }}>Remove</Button>}</div>
        <div className="flex flex-wrap gap-x-5 gap-y-2">{namespaceRoles.map(r => {
          const implicit = isOwner && ['admin', 'member', 'developer', 'operator'].includes(r);
          return <label key={r} title={roleDescriptions[r]} className="flex items-center gap-2 text-xs capitalize"><input type="checkbox" aria-label={`${a?.username || id} ${r}`} checked={implicit || !!members[id]?.includes(r)} disabled={(isOwner && r !== "auditor") || a?.disabled} onChange={event => { const assigned = members[id] || []; const next = event.target.checked ? [...assigned, r] : assigned.filter(x => x !== r); const value = { ...members, [id]: next }; if (isOwner && next.length === 0) delete value[id]; onChange(value); }} />{r}</label>;
        })}</div>
      </div>;
    })}</div>
    <div className="grid items-start gap-3 sm:grid-cols-[minmax(0,1fr)_auto_auto]">
      <AccountPicker namespace={namespace} value={account} onChange={setAccount} exclude={ids} label="Find a member" />
      <select aria-label="Member role" value={role} onChange={event => setRole(event.target.value)} className="h-10 rounded-lg border bg-white px-3 text-sm">{namespaceRoles.map(r => <option key={r} value={r}>{r}</option>)}</select>
      <Button type="button" variant="outline" disabled={!account} onClick={() => { onChange({ ...members, [account]: [role] }); setAccount(''); }}>Add member</Button>
    </div>
    <p className="text-xs leading-5 text-slate-500">Namespace administration and private-work access are separate. Assign Auditor explicitly only when access to all private work is required.</p>
  </div>;
}
