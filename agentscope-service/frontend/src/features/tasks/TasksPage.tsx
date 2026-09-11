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

/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

import { useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Bot, ChevronRight, Search } from 'lucide-react';
import { Link } from 'react-router-dom';

import { listTasks } from '@/api/collaboration';
import { useControlPlaneScope } from '@/app/ScopeContext';
import { EntityIdentityText, entityDisplayName, useEntityIdentities } from '@/components/EntityIdentity';
import { EmptyState } from '@/components/EmptyState';
import { Page, PageHeader } from '@/components/Page';
import { Badge } from '@/components/ui/badge';
import { Card } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { formatRelative } from '@/lib/format';

function tone(status: string): 'default' | 'success' | 'warning' | 'danger' | 'info' {
  if (status === 'completed') return 'success';
  if (['failed', 'cancelled'].includes(status)) return 'danger';
  if (['running', 'dispatched'].includes(status)) return 'info';
  return 'warning';
}

export default function TasksPage() {
  const scope = useControlPlaneScope();
  const [status, setStatus] = useState('');
  const [search, setSearch] = useState('');
  const tasks = useQuery({
    queryKey: ['agent-tasks', scope.tenant, scope.namespace, status],
    queryFn: () => listTasks(scope.tenant, scope.namespace, status),
    refetchInterval: 5000,
  });
  const identities = useEntityIdentities((tasks.data?.items || []).flatMap(task => [
    { type: 'agent', ref: task.agentId },
    { type: 'issue', ref: task.issueId },
    { type: 'team', ref: task.teamId },
  ]));
  const items = useMemo(() => {
    const needle = search.trim().toLowerCase();
    return (tasks.data?.items || []).filter((task) => !needle || [task.id, task.issueId, task.agentId, entityDisplayName(identities, 'agent', task.agentId), entityDisplayName(identities, 'issue', task.issueId), task.teamRole, task.triggerType, task.status].filter(Boolean).join(' ').toLowerCase().includes(needle));
  }, [identities, search, tasks.data?.items]);

  return (
    <Page>
      <PageHeader
        title="Agent tasks"
        description="Execution obligations created by assignment, discussion, delegation, automation, or retry."
      />
      <div className="flex flex-col gap-2 sm:flex-row sm:justify-end">
        <label className="relative w-full sm:w-80">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
          <Input className="pl-9 shadow-none" value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Search tasks" />
        </label>
        <select className="h-10 rounded-lg border border-slate-200 bg-white px-3 text-sm" value={status} onChange={(event) => setStatus(event.target.value)}>
          <option value="">All statuses</option>
          <option value="pending">Pending</option>
          <option value="dispatched">Dispatched</option>
          <option value="running">Running</option>
          <option value="completed">Completed</option>
          <option value="failed">Failed</option>
          <option value="cancelled">Cancelled</option>
        </select>
      </div>
      {!tasks.isLoading && !items.length ? (
        <EmptyState title={search ? 'No matching tasks' : 'No agent tasks'} description={search ? 'Try another search or status filter.' : 'Assign or mention an Agent from an Issue to create work.'} />
      ) : (
        <Card className="overflow-hidden">
          <div className="flex items-center justify-between border-b border-slate-100 px-5 py-3 text-xs text-slate-500"><span>{tasks.isLoading ? 'Loading tasks…' : `${items.length} task${items.length === 1 ? '' : 's'}`}</span><span className="hidden sm:block">Updated automatically</span></div>
          <div className="hidden overflow-x-auto md:block">
            <table className="w-full min-w-[780px] text-left text-sm">
              <thead className="border-b border-slate-100 bg-slate-50/70 text-[11px] font-semibold uppercase tracking-[0.08em] text-slate-400"><tr><th className="px-5 py-3">Task</th><th className="px-4 py-3">Issue</th><th className="px-4 py-3">Agent / role</th><th className="px-4 py-3">Inputs</th><th className="px-4 py-3">Status</th><th className="w-12 px-4 py-3"><span className="sr-only">Open</span></th></tr></thead>
              <tbody className="divide-y divide-slate-100">{items.map((task) => (
                <tr key={task.id} className="group hover:bg-slate-50/70">
                  <td className="px-5 py-4"><Link className="font-mono text-sm font-medium text-slate-900 group-hover:text-indigo-700" to={scope.scopedPath(`/work/executions/tasks/${task.id}`)}>{task.id.slice(0, 10)}</Link><div className="mt-1 text-xs text-slate-400">{formatRelative(task.createdAt)}</div></td>
                  <td className="px-4 py-4"><Link className="text-xs text-slate-600 hover:text-indigo-700" to={scope.scopedPath(`/work/issues/${task.issueId}`)}><EntityIdentityText identities={identities} type="issue" entityRef={task.issueId} secondary /></Link></td>
                  <td className="px-4 py-4"><EntityIdentityText identities={identities} type="agent" entityRef={task.agentId} /><div className="mt-1 text-xs text-slate-400">{task.teamRole || task.triggerType}</div></td>
                  <td className="px-4 py-4 text-slate-600">{task.inputs?.length || 0}</td>
                  <td className="px-4 py-4"><Badge tone={tone(task.status)}>{task.status.replace(/_/g, ' ')}</Badge></td>
                  <td className="px-4 py-4 text-slate-300"><ChevronRight className="h-4 w-4" /></td>
                </tr>
              ))}</tbody>
            </table>
          </div>
          <div className="divide-y divide-slate-100 md:hidden">{items.map((task) => (
            <Link key={task.id} to={scope.scopedPath(`/work/executions/tasks/${task.id}`)} className="flex gap-3 px-4 py-4 hover:bg-slate-50">
              <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg border border-slate-200 text-slate-500"><Bot className="h-4 w-4" /></span>
              <div className="min-w-0 flex-1"><div className="truncate font-mono text-sm font-medium text-slate-900">{task.id.slice(0, 10)}</div><div className="mt-1 truncate text-xs text-slate-500"><EntityIdentityText identities={identities} type="agent" entityRef={task.agentId} /> · {formatRelative(task.createdAt)}</div><Badge className="mt-2" tone={tone(task.status)}>{task.status.replace(/_/g, ' ')}</Badge></div>
              <ChevronRight className="mt-2 h-4 w-4 text-slate-300" />
            </Link>
          ))}</div>
        </Card>
      )}
    </Page>
  );
}
