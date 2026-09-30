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
import { useMutation, useQueryClient } from '@tanstack/react-query';
import type { Issue } from '@/api/collaboration';
import { updateIssueAccess, type IssueAccess } from '@/api/permissions';
import { getToken, getUsername } from '@/lib/auth';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';

function currentAccount() { try { return String(JSON.parse(atob((getToken() || '').split('.')[1].replace(/-/g, '+').replace(/_/g, '/'))).sub || ''); } catch { return ''; } }
export function IssueSharing({ issue }: { issue: Issue }) {
  const qc = useQueryClient();
  const [policy, setPolicy] = useState<IssueAccess>(issue.access ?? { mode: 'private' });
  const [account, setAccount] = useState('');
  const [role, setRole] = useState<'reader' | 'contributor'>('reader');
  useEffect(() => setPolicy(issue.access ?? { mode: 'private' }), [issue.access, issue.id]);
  const owned = issue.creator.type === 'human' && [currentAccount(), getUsername()].includes(issue.creator.ref || '');
  const save = useMutation({ mutationFn: () => updateIssueAccess(issue.id, issue.version, policy), onSuccess: () => qc.invalidateQueries() });
  if (issue.parentIssueId) return <section className="border-t pt-4 text-xs text-muted-foreground">Sharing is inherited from the root Issue.</section>;
  return <section className="space-y-3 border-t pt-4"><h2 className="text-sm font-semibold">Sharing</h2>
    {owned ? <>
      <select aria-label="Issue sharing" className="h-9 w-full rounded-md border bg-white px-2 text-sm" value={policy.mode} onChange={e => setPolicy({ mode: e.target.value as IssueAccess['mode'], members: undefined })}><option value="private">Private</option><option value="shared">Selected members</option><option value="namespace">Namespace members</option></select>
      {policy.mode === 'shared' && <>
        {Object.entries(policy.members ?? {}).map(([id, r]) => <div key={id} className="flex items-center gap-1 text-xs"><span className="min-w-0 flex-1 truncate" title={id}>{id}</span><span>{r}</span><Button variant="ghost" size="sm" onClick={() => { const members = { ...policy.members }; delete members[id]; setPolicy({ ...policy, members }); }}>Remove</Button></div>)}
        <Input aria-label="Collaborator account ID" value={account} onChange={e => setAccount(e.target.value)} placeholder="Namespace member account ID" />
        <div className="flex gap-2"><select aria-label="Collaborator permission" className="min-w-0 flex-1 rounded border px-2 text-xs" value={role} onChange={e => setRole(e.target.value as typeof role)}><option value="reader">Reader</option><option value="contributor">Contributor</option></select><Button variant="outline" size="sm" disabled={!account.trim()} onClick={() => { setPolicy({ ...policy, members: { ...policy.members, [account.trim()]: role } }); setAccount(''); }}>Add</Button></div>
      </>}
      <Button size="sm" disabled={save.isPending} onClick={() => save.mutate()}>Save sharing</Button>
      {save.error && <p role="alert" className="text-xs text-destructive">{save.error.message}</p>}
      {save.isSuccess && <p className="text-xs text-emerald-700">Sharing saved.</p>}
    </> : <p className="text-xs capitalize">{issue.access?.mode || 'private'}</p>}
    <p className="text-xs text-muted-foreground">Applies to child Issues, comments, execution records and attachments. Agent developers do not automatically receive access.</p>
  </section>;
}
