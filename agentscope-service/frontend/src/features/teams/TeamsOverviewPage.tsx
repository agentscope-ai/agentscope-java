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

import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Page, PageHeader } from '@/components/Page';
import { listTeams, teamPhaseTone } from '@/api/teams';

export default function TeamsOverviewPage() {
  const teams = useQuery({
    queryKey: ['teams', 'overview'],
    queryFn: () => listTeams(),
    refetchInterval: 15_000,
  });

  const items = teams.data?.items || [];
  const running = items.filter((t) => t.phase === 'Running').length;
  const idle = items.filter((t) => t.phase === 'Idle').length;
  const pending = items.filter((t) => t.phase === 'Pending').length;
  const completed = items.filter((t) => t.phase === 'Completed').length;
  const failed = items.filter((t) => t.phase === 'Failed').length;

  return (
    <Page>
      <PageHeader
        title="Teams"
        description="Claude-parity Agent Teams: shared task board, peer mailbox, Managed + BYO members."
        actions={
          <Button asChild>
            <Link to="/teams/new">New team</Link>
          </Button>
        }
      />

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
        {[
          { label: 'Total', value: items.length },
          { label: 'Running', value: running },
          { label: 'Idle', value: idle },
          { label: 'Pending', value: pending },
          { label: 'Done / Failed', value: `${completed} / ${failed}` },
        ].map((c) => (
          <div
            key={c.label}
            className="rounded-xl border border-border bg-white px-5 py-4 shadow-sm"
          >
            <div className="text-sm text-muted-foreground">{c.label}</div>
            <div className="mt-1 text-2xl font-semibold tracking-tight">{c.value}</div>
          </div>
        ))}
      </div>

      <section className="space-y-3">
        <div className="flex items-center justify-between">
          <h2 className="text-lg font-semibold">Recent teams</h2>
          <Button variant="outline" size="sm" asChild>
            <Link to="/teams/list">View all</Link>
          </Button>
        </div>
        {teams.isLoading && (
          <p className="text-sm text-muted-foreground">Loading teams…</p>
        )}
        {teams.isError && (
          <p className="text-sm text-red-600">Failed to load teams.</p>
        )}
        {!teams.isLoading && items.length === 0 && (
          <div className="rounded-xl border border-dashed border-border bg-white px-6 py-10 text-center">
            <p className="text-sm text-muted-foreground">
              No teams yet. Create a lead + workers to start coordinating.
            </p>
            <Button className="mt-4" asChild>
              <Link to="/teams/new">Create team</Link>
            </Button>
          </div>
        )}
        <ul className="divide-y divide-border overflow-hidden rounded-xl border border-border bg-white shadow-sm">
          {items.slice(0, 8).map((t) => (
            <li key={`${t.namespace}/${t.name}`}>
              <Link
                to={`/teams/${encodeURIComponent(t.name)}?namespace=${encodeURIComponent(t.namespace || 'default')}`}
                className="flex items-center justify-between gap-4 px-5 py-4 transition-colors hover:bg-muted/40"
              >
                <div className="min-w-0">
                  <div className="truncate font-medium">{t.name}</div>
                  <div className="truncate text-sm text-muted-foreground">
                    {t.objective || '—'}
                  </div>
                </div>
                <Badge tone={teamPhaseTone(t.phase)}>{t.phase || 'unknown'}</Badge>
              </Link>
            </li>
          ))}
        </ul>
      </section>
    </Page>
  );
}
