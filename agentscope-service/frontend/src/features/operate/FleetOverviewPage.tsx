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
import { Card, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { EmptyState } from '@/components/EmptyState';
import { Page, PageHeader } from '@/components/Page';
import { Button } from '@/components/ui/button';
import { fetchOverview, fetchOverviewTimeseries } from './api';
import { HealthBanner } from './components/HealthBanner';
import { TokenTrend } from './components/TokenTrend';
import {
  TopAgentsByActiveTable,
  TopAgentsByTokensTable,
  TopSessionsByDurationTable,
  TopSessionsByTokensTable,
} from './components/TopAgentsTable';

function Stat({
  label,
  value,
  to,
}: {
  label: string;
  value: string | number;
  to?: string;
}) {
  const body = (
    <Card className={to ? 'h-full transition hover:border-indigo-200 hover:shadow-md' : undefined}>
      <CardHeader className="pb-3">
        <CardDescription className={to ? 'text-primary' : undefined}>{label}</CardDescription>
        <CardTitle className="font-mono text-2xl tabular-nums">{value}</CardTitle>
      </CardHeader>
    </Card>
  );
  if (!to) return body;
  return (
    <Link to={to} className="block rounded-xl focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
      {body}
    </Link>
  );
}

export default function FleetOverviewPage() {
  const overview = useQuery({
    queryKey: ['overview'],
    queryFn: fetchOverview,
    refetchInterval: 10_000,
    refetchIntervalInBackground: false,
  });
  const timeseries = useQuery({
    queryKey: ['overview-timeseries', 'tokens'],
    queryFn: () => fetchOverviewTimeseries({ metric: 'tokens', bucket: '1h' }),
    refetchInterval: 60_000,
    refetchIntervalInBackground: false,
    retry: false,
  });

  const o = overview.data;
  const phases = o?.sessionsByPhase || {};
  const idleCount = phases.idle ?? 0;
  const healthy = o?.healthyInstanceCount ?? o?.instanceCount ?? 0;
  const stale = o?.staleInstanceCount ?? 0;

  return (
    <Page>
      <PageHeader
        title="Fleet overview"
        description="Cross-framework agent instances and runtime sessions reported into aistiod."
      />

      {overview.isError && (
        <EmptyState
          title="Overview unavailable"
          description="The /api/v1/overview endpoint did not respond. Ensure aistiod is running with the runtime store."
        />
      )}

      {o && (
        <HealthBanner
          staleDataplanes={o.staleDataplanes}
          orphanSessions={o.orphanSessions}
        />
      )}

      {o && (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4 xl:grid-cols-7">
          <Stat label="Agents" value={o.agentCount} to="/operate/agents" />
          <Stat
            label="Healthy instances"
            value={healthy}
            to="/operate/agents?presence=live"
          />
          <Stat
            label="Stale instances"
            value={stale}
            to="/operate/agents?presence=offline"
          />
          <Stat
            label="Active sessions"
            value={o.activeSessionCount}
            to="/operate/sessions?phase=active"
          />
          <Stat
            label="Idle sessions"
            value={idleCount}
            to="/operate/sessions?phase=idle"
          />
          <Stat label="Tokens (24h, Δ)" value={o.tokenUsage24h.toLocaleString()} />
          <Stat label="Errors (24h)" value={o.errorCount24h ?? 0} />
        </div>
      )}

      {o && ((o.offlineAgentCount ?? 0) > 0 || (o.historicalAgentCount ?? 0) > 0) && (
        <p className="text-sm text-muted-foreground">
          Agents counts live instances only.
          {(o.offlineAgentCount ?? 0) > 0 && (
            <>
              {' '}
              <Link className="text-primary underline-offset-2 hover:underline" to="/operate/agents?presence=offline">
                {o.offlineAgentCount} offline
              </Link>
            </>
          )}
          {(o.historicalAgentCount ?? 0) > 0 && (
            <>
              {(o.offlineAgentCount ?? 0) > 0 ? ' · ' : ' '}
              <Link className="text-primary underline-offset-2 hover:underline" to="/operate/agents?presence=historical">
                {o.historicalAgentCount} historical
              </Link>
            </>
          )}
        </p>
      )}

      <TokenTrend
        points={timeseries.data?.points}
        loading={timeseries.isLoading}
        error={timeseries.isError}
      />

      {o && (
        <div className="grid gap-4 lg:grid-cols-2">
          <TopAgentsByTokensTable agents={o.topAgents} />
          <TopSessionsByTokensTable sessions={o.topSessionsByTokens} />
          <TopSessionsByDurationTable sessions={o.topSessionsByDuration} />
          <TopAgentsByActiveTable agents={o.topAgentsByActive} />
        </div>
      )}

      {!overview.isLoading && o && o.dataplaneCount === 0 && o.agentCount === 0 && (
        <EmptyState
          title="No data planes registered"
          description="Start a data plane that implements /agentscope/* and self-registers with aistiod, or connect Kubernetes for CRD discovery."
          action={
            <Button asChild variant="outline">
              <Link to="/operate/agents">Open agents</Link>
            </Button>
          }
        />
      )}
    </Page>
  );
}
