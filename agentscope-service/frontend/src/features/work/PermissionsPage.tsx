import { useEffect, useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { createNamespace, getNamespace, updateNamespace, type Namespace } from '@/api/permissions';
import { useControlPlaneScope } from '@/app/ScopeContext';
import { namespaceCan } from '@/lib/namespaceScope';
import { isAdmin } from '@/lib/auth';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';

const roles = ['viewer', 'member', 'developer', 'operator', 'admin', 'auditor'];
const descriptions: Record<string, string> = {
  viewer: 'Discover resources and read work shared with you.',
  member: 'Create work and use Agents, Teams and Workflows.',
  developer: 'Configure and publish resources; create and run work.',
  operator: 'Inspect and operate namespace infrastructure.',
  admin: 'Manage members, resources and infrastructure.',
  auditor: 'Read all business work, including private Issues and execution data.',
};

export default function PermissionsPage() {
  const scope = useControlPlaneScope();
  const canManage = namespaceCan(scope.roles, 'manage') || isAdmin();
  const query = useQuery({ queryKey: ['namespace-permissions', scope.tenant, scope.namespace], queryFn: () => getNamespace(scope.namespace), enabled: canManage });
  const [draft, setDraft] = useState<Namespace>();
  const [account, setAccount] = useState('');
  const [role, setRole] = useState('member');
  const [name, setName] = useState('');
  const [displayName, setDisplayName] = useState('');
  useEffect(() => setDraft(query.data?.namespace), [query.data]);
  const save = useMutation({ mutationFn: () => updateNamespace(draft!), onSuccess: () => { void query.refetch(); scope.refreshNamespaces(); } });
  const create = useMutation({ mutationFn: () => createNamespace(name.trim(), displayName.trim()), onSuccess: () => { setName(''); setDisplayName(''); scope.refreshNamespaces(); } });
  const error = save.error || create.error || query.error;
  return <main className="mx-auto w-full max-w-4xl space-y-8 p-8">
    <div><h1 className="text-2xl font-semibold">Namespace permissions</h1><p className="mt-2 text-sm text-muted-foreground">Manage access to resources and work in {scope.namespace}.</p></div>
    <section className="rounded-xl border bg-white p-5"><h2 className="font-medium">Your access</h2><div className="mt-3 flex flex-wrap gap-2">{scope.roles.map(r => <span key={r} className="rounded-full bg-slate-100 px-3 py-1 text-sm capitalize">{r}</span>)}</div><p className="mt-3 text-sm text-muted-foreground">Agent access does not grant access to another person's private work. Cross-namespace assignment is disabled.</p></section>
    {error && <p role="alert" className="text-sm text-destructive">{error instanceof Error ? error.message : 'Unable to update permissions'}</p>}
    {canManage && draft && <section className="space-y-4 rounded-xl border bg-white p-5">
      <h2 className="font-medium">Members</h2><p className="text-sm text-muted-foreground">Owner: <span className="font-mono">{draft.owner}</span>. An administrator can manage membership; the auditor role separately grants access to private work.</p>
      {draft.kind === 'personal' ? <p className="text-sm">Personal namespace membership is fixed to its owner.</p> : <>
        {Object.entries(draft.members ?? {}).map(([id, assigned]) => <div key={id} className="space-y-2 border-b pb-3"><div className="flex items-center justify-between gap-4"><span className="break-all text-sm font-mono">{id}</span><Button variant="ghost" size="sm" onClick={() => { const next = { ...draft.members }; delete next[id]; setDraft({ ...draft, members: next }); }}>Remove</Button></div><div className="flex flex-wrap gap-3">{roles.map(r => <label key={r} title={descriptions[r]} className="flex items-center gap-1 text-xs capitalize"><input type="checkbox" checked={assigned.includes(r)} onChange={e => setDraft({ ...draft, members: { ...draft.members, [id]: e.target.checked ? [...assigned, r] : assigned.filter(v => v !== r) } })} />{r}</label>)}</div></div>)}
        <div className="flex flex-wrap gap-2"><Input aria-label="Account ID" placeholder="Account ID" value={account} onChange={e => setAccount(e.target.value)} className="min-w-52 flex-1" /><select aria-label="Member role" value={role} onChange={e => setRole(e.target.value)} className="rounded-md border px-2 text-sm">{roles.map(r => <option key={r}>{r}</option>)}</select><Button variant="outline" disabled={!account.trim()} onClick={() => { setDraft({ ...draft, members: { ...draft.members, [account.trim()]: [role] } }); setAccount(''); }}>Add member</Button></div>
        <Button disabled={save.isPending || Object.values(draft.members).some(rs => !rs.length)} onClick={() => save.mutate()}>{save.isPending ? 'Saving…' : 'Save membership'}</Button>
        {save.isSuccess && <p className="text-sm text-emerald-700">Membership saved.</p>}
      </>}
    </section>}
    <section className="rounded-xl border bg-white p-5"><h2 className="mb-3 font-medium">Role permissions</h2><dl className="grid gap-3 text-sm">{roles.map(r => <div key={r} className="grid grid-cols-[6rem_1fr] gap-3"><dt className="font-medium capitalize">{r}</dt><dd className="text-muted-foreground">{descriptions[r]}</dd></div>)}</dl></section>
    {isAdmin() && <section className="space-y-3 rounded-xl border bg-white p-5"><h2 className="font-medium">Create a shared namespace</h2><p className="text-sm text-muted-foreground">Provision an existing namespace name to manage its resources, or choose a new name for a new space.</p><Input aria-label="Namespace name" placeholder="engineering" value={name} onChange={e => setName(e.target.value)} /><Input aria-label="Namespace display name" placeholder="Engineering" value={displayName} onChange={e => setDisplayName(e.target.value)} /><Button disabled={!name.trim() || !displayName.trim() || create.isPending} onClick={() => create.mutate()}>Create namespace</Button>{create.isSuccess && <p className="text-sm text-emerald-700">Namespace created. Select it in the sidebar to manage members.</p>}</section>}
  </main>;
}
