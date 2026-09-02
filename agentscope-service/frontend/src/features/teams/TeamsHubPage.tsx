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
import { EmptyState } from '@/components/EmptyState';
import { Page, PageHeader } from '@/components/Page';
import { listTeams, teamPhaseTone } from '@/api/teams';
import { useT } from '@/i18n';
import { teamPhaseLabel } from './i18n';

export default function TeamsHubPage() {
  const t = useT();
  const teams = useQuery({
    queryKey: ['teams', 'list'],
    queryFn: () => listTeams(),
    refetchInterval: 10_000,
  });

  const items = teams.data?.items || [];

  return (
    <Page>
      <PageHeader
        title={t('teams.title')}
        description={t('teams.hub.description')}
        actions={
          <Button asChild>
            <Link to="/teams/new">{t('teams.new')}</Link>
          </Button>
        }
      />

      {teams.isLoading && <p className="text-sm text-muted-foreground">{t('common.loading')}</p>}
      {teams.isError && (
        <p className="text-sm text-red-600">
          {t('teams.loadFailed')}
        </p>
      )}

      {!teams.isLoading && items.length === 0 && (
        <EmptyState
          title={t('teams.empty.title')}
          description={t('teams.empty.description')}
          action={
            <Button asChild>
              <Link to="/teams/new">{t('teams.create')}</Link>
            </Button>
          }
        />
      )}

      {items.length > 0 && (
        <div className="overflow-hidden rounded-xl border border-border bg-white shadow-sm">
          <table className="w-full text-left text-sm">
            <thead className="border-b border-border bg-muted/40 text-muted-foreground">
              <tr>
                <th className="px-4 py-3 font-medium">{t('teams.name')}</th>
                <th className="px-4 py-3 font-medium">{t('teams.namespace')}</th>
                <th className="px-4 py-3 font-medium">{t('teams.phase')}</th>
                <th className="px-4 py-3 font-medium">{t('teams.lead')}</th>
                <th className="px-4 py-3 font-medium">{t('teams.objective')}</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {items.map((team) => (
                <tr key={`${team.namespace}/${team.name}`} className="hover:bg-muted/30">
                  <td className="px-4 py-3">
                    <Link
                      className="font-medium text-primary hover:underline"
                      to={`/teams/${encodeURIComponent(team.name)}?namespace=${encodeURIComponent(team.namespace || 'default')}`}
                    >
                      {team.name}
                    </Link>
                  </td>
                  <td className="px-4 py-3 text-muted-foreground">{team.namespace}</td>
                  <td className="px-4 py-3">
                    <Badge tone={teamPhaseTone(team.phase)}>{teamPhaseLabel(t, team.phase)}</Badge>
                  </td>
                  <td className="px-4 py-3 font-mono text-xs">{team.leadRef || '—'}</td>
                  <td className="max-w-xs truncate px-4 py-3 text-muted-foreground">
                    {team.objective}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Page>
  );
}
