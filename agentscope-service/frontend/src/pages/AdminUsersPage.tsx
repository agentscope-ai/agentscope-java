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
import { Link, Navigate, useSearchParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Plus, UsersRound } from 'lucide-react';
import { createUser, listUsers, resetPassword, setAccountDisabled, updateRoles, type AdminUserView } from '@/api/admin';
import { getNamespace, listAccountNamespaces, listManagedNamespaces, updateNamespace } from '@/api/permissions';
import { getUserId, isAdmin } from '@/api/auth';
import { useControlPlaneScope } from '@/app/ScopeContext';
import { Page, PageHeader } from '@/components/Page';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Badge } from '@/components/ui/badge';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogBody } from '@/components/ui/dialog';
import { accountLabel, ErrorNotice, namespaceRoles, roleDescriptions, RoleBadges } from '@/features/settings/AccessComponents';

export default function AdminUsersPage() {
  const admin = isAdmin(); const qc = useQueryClient(); const scope = useControlPlaneScope();
  const [params, setParams] = useSearchParams(); const [search, setSearch] = useState(''); const [creating, setCreating] = useState(false); const [generated, setGenerated] = useState('');
  const query = useQuery({ queryKey: ['admin-users'], queryFn: listUsers, enabled: admin });
  const selected = query.data?.find(a => a.userId === params.get('user'));
  const refresh = async () => { await qc.invalidateQueries({ queryKey: ['admin-users'] }); await qc.invalidateQueries({ queryKey: ['account-audit'] }); scope.refreshNamespaces(); };
  if (!admin) return <Navigate to="/settings/namespaces" replace />;
  const users = query.data?.filter(a => `${a.username} ${a.displayName} ${a.userId}`.toLowerCase().includes(search.toLowerCase())) || [];
  return <Page><PageHeader title="Users" description="Manage platform accounts and each person's access to shared namespaces." actions={<Button onClick={() => setCreating(true)}><Plus className="h-4 w-4" />Create user</Button>} />
    <Input className="max-w-sm" aria-label="Search users" placeholder="Search by name or username" value={search} onChange={e => setSearch(e.target.value)} />
    <ErrorNotice error={query.error} />
    <div className={`grid items-start gap-6 ${selected ? 'xl:grid-cols-[minmax(0,1fr)_minmax(0,1.1fr)]' : ''}`}><section className="overflow-x-auto rounded-xl border"><table className="w-full text-left text-sm"><thead className="bg-slate-50 text-xs text-slate-500"><tr><th className="p-4 font-medium">Account</th><th className="p-4 font-medium">Platform role</th><th className="p-4 font-medium">Status</th></tr></thead><tbody className="divide-y">{users.map(a => <tr key={a.userId} className={selected?.userId === a.userId ? 'bg-indigo-50/50' : 'hover:bg-slate-50'}><td className="p-4"><button className="text-left font-medium text-slate-900 hover:text-indigo-600" onClick={() => { const next = new URLSearchParams(params); next.set('user', a.userId); setParams(next); }}>{accountLabel(a)}</button><p className="mt-1 break-all text-xs text-slate-400">{a.userId}</p></td><td className="p-4"><Badge>{a.roles.includes('admin') ? 'Platform admin' : 'User'}</Badge></td><td className="p-4"><Badge tone={a.disabled ? 'default' : 'success'}>{a.disabled ? 'Disabled' : 'Active'}</Badge></td></tr>)}</tbody></table>{query.isLoading ? <p className="p-6 text-sm text-slate-500">Loading accounts…</p> : users.length === 0 && <p className="p-8 text-center text-sm text-slate-500">No matching accounts.</p>}</section>
      {selected && <UserDetail key={selected.userId} user={selected} onChanged={refresh} />}
    </div>
    <Dialog open={creating} onOpenChange={setCreating}><DialogContent size="md"><DialogHeader><DialogTitle>Create user</DialogTitle><DialogDescription>Create an account, then assign access to the namespaces they need.</DialogDescription></DialogHeader><DialogBody>{creating && <CreateUserForm onCreated={password => { setCreating(false); setGenerated(password || ''); void refresh(); }} />}</DialogBody></DialogContent></Dialog>
    <Dialog open={!!generated} onOpenChange={open => { if (!open) setGenerated(''); }}><DialogContent size="md"><DialogHeader><DialogTitle>Account created</DialogTitle><DialogDescription>Share this initial password with the new user. It is only shown here once.</DialogDescription></DialogHeader><DialogBody><code className="block break-all rounded-lg bg-slate-50 p-4 text-lg">{generated}</code><Button className="mt-4" onClick={() => setGenerated('')}>Done</Button></DialogBody></DialogContent></Dialog>
  </Page>;
}

function CreateUserForm({ onCreated }: { onCreated: (password?: string) => void }) {
  const [username, setUsername] = useState(''); const [password, setPassword] = useState(''); const [admin, setAdmin] = useState(false);
  const create = useMutation({ mutationFn: () => createUser({ username: username.trim(), initialPassword: password || undefined, roles: admin ? ['user', 'admin'] : ['user'] }), onSuccess: r => onCreated(r.generatedPassword) });
  return <form className="space-y-4" onSubmit={e => { e.preventDefault(); create.mutate(); }}><label className="block space-y-2 text-sm font-medium">Username<Input required maxLength={100} value={username} onChange={e => setUsername(e.target.value)} autoComplete="off" /></label><label className="block space-y-2 text-sm font-medium">Initial password<Input type="password" minLength={6} value={password} onChange={e => setPassword(e.target.value)} autoComplete="new-password" /><span className="block text-xs font-normal text-slate-500">Leave blank to generate a password.</span></label><label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={admin} onChange={e => setAdmin(e.target.checked)} />Platform administrator</label><ErrorNotice error={create.error} /><Button type="submit" disabled={!username.trim() || create.isPending}>{create.isPending ? 'Creating…' : 'Create user'}</Button></form>;
}

function UserDetail({ user, onChanged }: { user: AdminUserView; onChanged: () => Promise<void> }) {
  const [roles, setRoles] = useState(user.roles); const [dialog, setDialog] = useState<'status' | 'password' | null>(null); const [password, setPassword] = useState(''); const [notice, setNotice] = useState('');
  useEffect(() => setRoles(user.roles), [user]);
  const mutation = useMutation({ mutationFn: (action: 'roles' | 'status' | 'password') => action === 'roles' ? updateRoles(user.userId, roles, user.version) : action === 'status' ? setAccountDisabled(user.userId, !user.disabled, user.version) : resetPassword(user.userId, password), onSuccess: async () => { setDialog(null); setPassword(''); setNotice('Account updated.'); await onChanged(); } });
  return <aside className="space-y-5"><section className="space-y-4 rounded-xl border p-5"><div className="flex items-center gap-3"><span className="rounded-xl bg-indigo-50 p-3 text-indigo-600"><UsersRound className="h-5 w-5" /></span><div><h2 className="font-semibold">{accountLabel(user)}</h2><p className="mt-1 text-xs text-slate-500">{user.createdAt ? `Joined ${new Date(user.createdAt).toLocaleDateString()}` : user.userId}</p></div></div><h3 className="text-sm font-medium">Platform roles</h3><p className="text-xs leading-5 text-slate-500">Platform administration manages accounts and namespace provisioning. Namespace roles and private-work access are assigned separately.</p><div className="space-y-2">{['user', 'admin', 'agent_developer', 'operator'].map(r => <label key={r} className="flex items-center gap-2 text-sm"><input type="checkbox" checked={roles.includes(r)} onChange={e => setRoles(e.target.checked ? [...roles, r] : roles.filter(x => x !== r))} />{r === 'admin' ? 'Platform administrator' : r === 'user' ? 'Console user' : `${r} (legacy navigation)`}</label>)}</div><ErrorNotice error={mutation.error} />{notice && <p role="status" className="text-sm text-emerald-700">{notice}</p>}<Button disabled={!roles.length || mutation.isPending} onClick={() => mutation.mutate('roles')}>Save platform roles</Button><div className="flex flex-wrap gap-2 border-t pt-4"><Button variant="outline" onClick={() => setDialog('password')}>Reset password</Button><Button variant="outline" disabled={user.userId === getUserId()} onClick={() => setDialog('status')}>{user.disabled ? 'Enable account' : 'Disable account'}</Button></div></section>
    <UserNamespaceAccess user={user} />
    <Dialog open={dialog !== null} onOpenChange={open => { if (!open) setDialog(null); }}><DialogContent size="md"><DialogHeader><DialogTitle>{dialog === 'password' ? 'Reset password' : user.disabled ? 'Enable account' : 'Disable account'}</DialogTitle><DialogDescription>{dialog === 'password' ? `Set a new password for ${user.username}. Existing logins will be revoked.` : user.disabled ? 'This account can sign in again. Previous sessions remain revoked.' : 'Sign-in and existing sessions will be blocked. Historical work is retained. Transfer owned shared namespaces first.'}</DialogDescription></DialogHeader><DialogBody><div className="space-y-4">{dialog === 'password' && <Input aria-label="New account password" type="password" autoComplete="new-password" value={password} onChange={e => setPassword(e.target.value)} />}<ErrorNotice error={mutation.error} /><Button disabled={mutation.isPending || dialog === 'password' && password.length < 6} onClick={() => mutation.mutate(dialog === 'password' ? 'password' : 'status')}>{dialog === 'password' ? 'Reset password' : user.disabled ? 'Enable account' : 'Disable account'}</Button></div></DialogBody></DialogContent></Dialog>
  </aside>;
}

function UserNamespaceAccess({ user }: { user: AdminUserView }) {
  const qc = useQueryClient(); const scope = useControlPlaneScope();
  const memberships = useQuery({ queryKey: ['account-namespaces', user.userId], queryFn: () => listAccountNamespaces(user.userId) });
  const all = useQuery({ queryKey: ['managed-namespaces'], queryFn: listManagedNamespaces });
  const [name, setName] = useState(''); const [editing, setEditing] = useState('');
  const assigned = memberships.data?.items || [];
  const choices = all.data?.items.filter(n => n.kind === 'shared' && !n.archived && !assigned.some(a => a.name === n.name)) || [];
  return <section className="space-y-4 rounded-xl border p-5"><h3 className="font-semibold">Namespace access</h3><ErrorNotice error={memberships.error || all.error} /><div className="divide-y">{assigned.map(n => <div key={n.name} className="space-y-2 py-3"><div className="flex items-center justify-between gap-2"><Link to={`/settings/namespaces/${encodeURIComponent(n.name)}`} className="text-sm font-medium hover:text-indigo-600">{n.displayName}</Link><Badge>{n.owner === user.userId ? 'Owner' : n.archived ? 'Archived' : 'Member'}</Badge></div><RoleBadges roles={n.roles || []} />{n.groups && n.groups.length > 0 && <Link className="block text-xs text-indigo-600" to={`/settings/namespaces/${encodeURIComponent(n.name)}?tab=user+groups`}>Via user groups: {n.groups.join(', ')}</Link>}{n.kind === 'shared' && !n.archived && <Button variant="ghost" size="sm" onClick={() => setEditing(n.name)}>Edit namespace access</Button>}</div>)}</div>{!memberships.isLoading && assigned.length === 0 && <p className="text-sm text-slate-500">No namespace memberships yet. A personal space is created when this user first signs in.</p>}<div className="flex flex-wrap gap-2"><select aria-label="Assign namespace" className="h-10 min-w-0 flex-1 rounded-lg border bg-white px-3 text-sm" value={name} onChange={e => setName(e.target.value)}><option value="">Choose a shared namespace</option>{choices.map(n => <option key={n.name} value={n.name}>{n.displayName}</option>)}</select><Button variant="outline" disabled={!name || user.disabled} onClick={() => setEditing(name)}>Assign access</Button></div><Dialog open={!!editing} onOpenChange={open => { if (!open) setEditing(''); }}><DialogContent size="md"><DialogHeader><DialogTitle>Namespace access · {editing}</DialogTitle><DialogDescription>Set {user.username}'s direct roles. User group grants are managed in Namespace user groups.</DialogDescription></DialogHeader><DialogBody>{editing && <UserGrantEditor key={editing} name={editing} user={user} onSaved={async () => { setEditing(''); setName(''); await qc.invalidateQueries({ queryKey: ['account-namespaces'] }); await qc.invalidateQueries({ queryKey: ['namespace-detail'] }); await qc.invalidateQueries({ queryKey: ['managed-namespaces'] }); scope.refreshNamespaces(); }} />}</DialogBody></DialogContent></Dialog></section>;
}

function UserGrantEditor({ name, user, onSaved }: { name: string; user: AdminUserView; onSaved: () => Promise<void> }) {
  const query = useQuery({ queryKey: ['namespace-detail', name], queryFn: () => getNamespace(name) });
  const [roles, setRoles] = useState<string[]>(['member']);
  useEffect(() => { if (query.data) setRoles(query.data.namespace.members[user.userId] || ['member']); }, [query.data, user.userId]);
  const owner = query.data?.namespace.owner === user.userId;
  const save = useMutation({ mutationFn: (remove: boolean) => { const n = query.data!.namespace; const members = { ...n.members }; if (remove || owner && roles.length === 0) delete members[user.userId]; else members[user.userId] = roles; return updateNamespace({ ...n, members }); }, onSuccess: onSaved });
  return <div className="space-y-4"><ErrorNotice error={query.error || save.error} />{query.isLoading ? <p className="text-sm text-slate-500">Loading access…</p> : query.data && <>{owner && <p className="text-sm text-slate-500">The owner has administration, development and operation access. Transfer ownership from Namespace settings to remove this access.</p>}<div className="space-y-3">{namespaceRoles.map(r => { const implicit = owner && ['admin', 'member', 'developer', 'operator'].includes(r); return <label key={r} className="flex items-start gap-3 text-sm"><input className="mt-1" type="checkbox" checked={implicit || roles.includes(r)} disabled={(owner && r !== "auditor") || user.disabled} onChange={e => setRoles(e.target.checked ? [...roles, r] : roles.filter(v => v !== r))} /><span><span className="capitalize font-medium">{r}</span><span className="mt-1 block text-xs text-slate-500">{roleDescriptions[r]}</span></span></label>; })}</div><div className="flex gap-2"><Button disabled={save.isPending || !owner && !roles.length || user.disabled} onClick={() => save.mutate(false)}>Save access</Button>{!owner && query.data.namespace.members[user.userId] && <Button variant="outline" disabled={save.isPending} onClick={() => save.mutate(true)}>Remove direct access</Button>}</div></>}</div>;
}
