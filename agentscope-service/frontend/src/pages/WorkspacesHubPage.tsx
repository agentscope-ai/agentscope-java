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
import { useNavigate } from 'react-router-dom';
import { ChevronRight, FolderOpen, Plus, Trash2 } from 'lucide-react';
import { createWorkspace, deleteWorkspace, listWorkspaces, WorkspaceSummary } from '../api/workspaces';
import { useControlPlaneScope } from '../app/ScopeContext';
import { EmptyState } from '../components/EmptyState';
import { Page, PageHeader } from '../components/Page';
import { Button } from '../components/ui/button';
import { Card } from '../components/ui/card';
import { Dialog, DialogBody, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '../components/ui/dialog';
import { Input } from '../components/ui/input';

export default function WorkspacesHubPage() {
  const navigate = useNavigate();
  const scope = useControlPlaneScope();
  const [items, setItems] = useState<WorkspaceSummary[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [name, setName] = useState('');
  const [creating, setCreating] = useState(false);
  const [createOpen, setCreateOpen] = useState(false);

  async function reload() {
    try {
      setItems(await listWorkspaces());
      setErr(null);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Failed to load');
    }
  }

  useEffect(() => {
    reload();
  }, []);

  async function onCreate() {
    if (!name.trim()) return;
    setCreating(true);
    try {
      const ws = await createWorkspace({ name: name.trim() });
      setName('');
      setCreateOpen(false);
      navigate(scope.scopedPath(`/agent-center/workspaces/${encodeURIComponent(ws.id)}`));
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Create failed');
    } finally {
      setCreating(false);
    }
  }

  return (
    <Page className="max-w-[1200px]">
      <PageHeader
        title="Workspaces"
        description="Author skills, tools, subagents, and AGENTS.md in a reusable workspace, then link Agents to it."
        actions={<Button onClick={() => setCreateOpen(true)}><Plus className="h-4 w-4" />New workspace</Button>}
      />
      {err && <div className="rounded-xl border border-red-100 bg-red-50 px-4 py-3 text-sm text-red-700">{err}</div>}
      {items.length ? (
        <Card className="overflow-hidden">
          <div className="border-b border-slate-100 px-5 py-3 text-xs text-slate-500">{items.length} workspace{items.length === 1 ? '' : 's'}</div>
          <div className="divide-y divide-slate-100">
            {items.map((workspace) => (
              <div key={workspace.id} className="group flex items-center gap-3 px-5 py-4 hover:bg-slate-50/70">
                <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg border border-slate-200 bg-white text-slate-500"><FolderOpen className="h-4 w-4" /></span>
                <button className="min-w-0 flex-1 text-left" onClick={() => navigate(scope.scopedPath(`/agent-center/workspaces/${encodeURIComponent(workspace.id)}`))}>
                  <div className="truncate text-sm font-semibold text-slate-900 group-hover:text-indigo-700">{workspace.name}</div>
                  <div className="mt-1 truncate text-xs text-slate-500">
                    {workspace.description || workspace.id} · v{workspace.version}{workspace.agentsMdExists ? ' · AGENTS.md' : ''} · {workspace.skillCount ?? 0} skills · {workspace.subagentCount ?? 0} subagents
                  </div>
                </button>
                <Button
                  variant="ghost"
                  size="icon"
                  className="text-slate-400 hover:text-red-600"
                  aria-label={`Delete ${workspace.name}`}
                  onClick={async () => {
                    if (!confirm(`Delete workspace ${workspace.name}?`)) return;
                    try {
                      await deleteWorkspace(workspace.id);
                      await reload();
                    } catch (cause: unknown) {
                      setErr(cause instanceof Error ? cause.message : 'Delete failed');
                    }
                  }}
                >
                  <Trash2 className="h-4 w-4" />
                </Button>
                <ChevronRight className="h-4 w-4 text-slate-300" />
              </div>
            ))}
          </div>
        </Card>
      ) : !err && <EmptyState title="No workspaces yet" description="Create a workspace to share tools, skills, and operating instructions across Agents." action={<Button size="sm" onClick={() => setCreateOpen(true)}>Create workspace</Button>} />}

      <Dialog open={createOpen} onOpenChange={setCreateOpen}>
        <DialogContent size="md">
          <DialogHeader><DialogTitle>Create workspace</DialogTitle><DialogDescription>Start a reusable home for Agent tools, skills, and instructions.</DialogDescription></DialogHeader>
          <DialogBody>
            <form onSubmit={(event) => { event.preventDefault(); void onCreate(); }} className="space-y-5">
              <label className="block space-y-2 text-sm font-medium text-slate-700">Name<Input value={name} onChange={(event) => setName(event.target.value)} placeholder="Research workspace" autoFocus required /></label>
              <div className="flex justify-end gap-2 border-t border-slate-100 pt-4"><Button type="button" variant="ghost" onClick={() => setCreateOpen(false)}>Cancel</Button><Button type="submit" disabled={creating || !name.trim()}>{creating ? 'Creating…' : 'Create workspace'}</Button></div>
            </form>
          </DialogBody>
        </DialogContent>
      </Dialog>
    </Page>
  );
}
