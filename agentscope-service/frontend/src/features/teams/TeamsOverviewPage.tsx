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
import { useI18n } from '@/i18n';
import { formatTeamNumber, teamPhaseLabel } from './i18n';

export default function TeamsOverviewPage() {
  const { locale, t } = useI18n();
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
        title={t('teams.title')}
        description={t('teams.overview.description')}
        actions={
          <Button asChild>
            <Link to="/teams/new">{t('teams.new')}</Link>
          </Button>
        }
      />

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
        {[
          { label: t('teams.total'), value: formatTeamNumber(locale, items.length) },
          { label: t('status.running'), value: formatTeamNumber(locale, running) },
          { label: t('status.idle'), value: formatTeamNumber(locale, idle) },
          { label: t('status.pending'), value: formatTeamNumber(locale, pending) },
          { label: t('teams.doneFailed'), value: `${formatTeamNumber(locale, completed)} / ${formatTeamNumber(locale, failed)}` },
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
          <h2 className="text-lg font-semibold">{t('teams.recent')}</h2>
          <Button variant="outline" size="sm" asChild>
            <Link to="/teams/list">{t('teams.viewAll')}</Link>
          </Button>
        </div>
        {teams.isLoading && (
          <p className="text-sm text-muted-foreground">{t('teams.loading')}</p>
        )}
        {teams.isError && (
          <p className="text-sm text-red-600">{t('teams.loadFailedShort')}</p>
        )}
        {!teams.isLoading && items.length === 0 && (
          <div className="rounded-xl border border-dashed border-border bg-white px-6 py-10 text-center">
            <p className="text-sm text-muted-foreground">
              {t('teams.empty.overview')}
            </p>
            <Button className="mt-4" asChild>
              <Link to="/teams/new">{t('teams.create')}</Link>
            </Button>
          </div>
        )}
        <ul className="divide-y divide-border overflow-hidden rounded-xl border border-border bg-white shadow-sm">
          {items.slice(0, 8).map((team) => (
            <li key={`${team.namespace}/${team.name}`}>
              <Link
                to={`/teams/${encodeURIComponent(team.name)}?namespace=${encodeURIComponent(team.namespace || 'default')}`}
                className="flex items-center justify-between gap-4 px-5 py-4 transition-colors hover:bg-muted/40"
              >
                <div className="min-w-0">
                  <div className="truncate font-medium">{team.name}</div>
                  <div className="truncate text-sm text-muted-foreground">
                    {team.objective || '—'}
                  </div>
                </div>
                <Badge tone={teamPhaseTone(team.phase)}>{teamPhaseLabel(t, team.phase)}</Badge>
              </Link>
            </li>
          ))}
        </ul>
      </section>
    </Page>
  );
}
