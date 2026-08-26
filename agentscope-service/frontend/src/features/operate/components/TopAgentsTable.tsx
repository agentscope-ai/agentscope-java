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

import { Link } from 'react-router-dom';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { useI18n } from '@/i18n';
import type { AgentUsage, SessionDurationRank, SessionUsage } from '../api';
import { phaseTone, sessionDetailPath } from '../api';
import { formatDuration, formatNumber, statusLabel } from '../i18n';

export function TopAgentsByTokensTable({ agents = [] }: { agents?: AgentUsage[] }) {
  const { locale, t } = useI18n();
  return (
    <Card className="h-full">
      <CardHeader>
        <CardTitle>{t('operate.rankings.agentsByTokens')}</CardTitle>
        <CardDescription>{t('operate.rankings.tokenDelta24h')}</CardDescription>
      </CardHeader>
      <CardContent>
        {agents.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t('operate.rankings.noAgentUsage')}</p>
        ) : (
          <div className="max-h-80 overflow-auto rounded-lg border border-border">
            <table className="w-full text-left text-sm">
              <thead className="sticky top-0 border-b border-border bg-muted/50 text-[13px] uppercase tracking-wide text-muted-foreground">
                <tr>
                  <th className="px-4 py-3 font-medium">#</th>
                  <th className="px-4 py-3 font-medium">{t('operate.fields.agent')}</th>
                  <th className="px-4 py-3 font-medium">{t('operate.fields.tokens')}</th>
                  <th className="px-4 py-3 font-medium">{t('status.active')}</th>
                  <th className="px-4 py-3 font-medium">{t('operate.fields.errors')}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {agents.map((a, i) => (
                  <tr key={`${a.namespace}/${a.agentName}`} className="hover:bg-muted/40">
                    <td className="px-4 py-3 font-mono tabular-nums text-muted-foreground">{i + 1}</td>
                    <td className="px-4 py-3">
                      <Link
                        className="font-medium text-primary hover:underline"
                        to={`/operate/agents/${encodeURIComponent(a.agentName)}?namespace=${encodeURIComponent(a.namespace || 'default')}`}
                      >
                        {a.agentName}
                      </Link>
                      <div className="text-sm text-muted-foreground">{a.namespace}</div>
                    </td>
                    <td className="px-4 py-3 font-mono tabular-nums">{formatNumber(locale, a.totalTokens || 0)}</td>
                    <td className="px-4 py-3 font-mono tabular-nums">{formatNumber(locale, a.activeSessions ?? 0)}</td>
                    <td className="px-4 py-3 font-mono tabular-nums text-muted-foreground">{formatNumber(locale, a.errorCount ?? 0)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

export function TopAgentsByActiveTable({ agents = [] }: { agents?: AgentUsage[] }) {
  const { locale, t } = useI18n();
  return (
    <Card className="h-full">
      <CardHeader>
        <CardTitle>{t('operate.rankings.agentsByActive')}</CardTitle>
        <CardDescription>{t('operate.rankings.peakActive5m')}</CardDescription>
      </CardHeader>
      <CardContent>
        {agents.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t('operate.rankings.noActiveSamples')}</p>
        ) : (
          <div className="max-h-80 overflow-auto rounded-lg border border-border">
            <table className="w-full text-left text-sm">
              <thead className="sticky top-0 border-b border-border bg-muted/50 text-[13px] uppercase tracking-wide text-muted-foreground">
                <tr>
                  <th className="px-4 py-3 font-medium">#</th>
                  <th className="px-4 py-3 font-medium">{t('operate.fields.agent')}</th>
                  <th className="px-4 py-3 font-medium">{t('operate.rankings.peakActive')}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {agents.map((a, i) => (
                  <tr key={`${a.namespace}/${a.agentName}`} className="hover:bg-muted/40">
                    <td className="px-4 py-3 font-mono tabular-nums text-muted-foreground">{i + 1}</td>
                    <td className="px-4 py-3">
                      <Link
                        className="font-medium text-primary hover:underline"
                        to={`/operate/agents/${encodeURIComponent(a.agentName)}?namespace=${encodeURIComponent(a.namespace || 'default')}`}
                      >
                        {a.agentName}
                      </Link>
                      <div className="text-sm text-muted-foreground">{a.namespace}</div>
                    </td>
                    <td className="px-4 py-3 font-mono tabular-nums">{formatNumber(locale, a.activeSessions ?? 0)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

export function TopSessionsByTokensTable({ sessions = [] }: { sessions?: SessionUsage[] }) {
  const { locale, t } = useI18n();
  return (
    <Card className="h-full">
      <CardHeader>
        <CardTitle>{t('operate.rankings.sessionsByTokens')}</CardTitle>
        <CardDescription>{t('operate.rankings.tokenDelta24h')}</CardDescription>
      </CardHeader>
      <CardContent>
        {sessions.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t('operate.rankings.noSessionUsage')}</p>
        ) : (
          <div className="max-h-80 overflow-auto rounded-lg border border-border">
            <table className="w-full text-left text-sm">
              <thead className="sticky top-0 border-b border-border bg-muted/50 text-[13px] uppercase tracking-wide text-muted-foreground">
                <tr>
                  <th className="px-4 py-3 font-medium">#</th>
                  <th className="px-4 py-3 font-medium">{t('operate.fields.session')}</th>
                  <th className="px-4 py-3 font-medium">{t('operate.fields.tokens')}</th>
                  <th className="px-4 py-3 font-medium">{t('operate.fields.phase')}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {sessions.map((s, i) => (
                  <tr key={s.sessionFk || s.sessionId} className="hover:bg-muted/40">
                    <td className="px-4 py-3 font-mono tabular-nums text-muted-foreground">{i + 1}</td>
                    <td className="px-4 py-3">
                      <Link
                        className="font-medium text-primary hover:underline"
                        to={sessionDetailPath({
                          id: s.sessionFk,
                          sessionId: s.sessionId,
                          agentName: s.agentName,
                          namespace: s.namespace,
                        })}
                      >
                        {s.sessionId}
                      </Link>
                      <div className="text-sm text-muted-foreground">{s.agentName}</div>
                    </td>
                    <td className="px-4 py-3 font-mono tabular-nums">{formatNumber(locale, s.totalTokens || 0)}</td>
                    <td className="px-4 py-3">
                      <Badge tone={phaseTone(s.phase)}>{statusLabel(t, s.phase)}</Badge>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

export function TopSessionsByDurationTable({ sessions = [] }: { sessions?: SessionDurationRank[] }) {
  const { locale, t } = useI18n();
  return (
    <Card className="h-full">
      <CardHeader>
        <CardTitle>{t('operate.rankings.turnsByDuration')}</CardTitle>
        <CardDescription>
          {t('operate.rankings.turnDurationDescription')}
        </CardDescription>
      </CardHeader>
      <CardContent>
        {sessions.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t('operate.rankings.noActiveTurns')}</p>
        ) : (
          <div className="max-h-80 overflow-auto rounded-lg border border-border">
            <table className="w-full text-left text-sm">
              <thead className="sticky top-0 border-b border-border bg-muted/50 text-[13px] uppercase tracking-wide text-muted-foreground">
                <tr>
                  <th className="px-4 py-3 font-medium">#</th>
                  <th className="px-4 py-3 font-medium">{t('operate.fields.session')}</th>
                  <th className="px-4 py-3 font-medium">{t('operate.fields.turn')}</th>
                  <th className="px-4 py-3 font-medium">{t('operate.fields.elapsed')}</th>
                  <th className="px-4 py-3 font-medium">{t('operate.fields.phase')}</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {sessions.map((s, i) => (
                  <tr key={s.sessionFk || s.sessionId} className="hover:bg-muted/40">
                    <td className="px-4 py-3 font-mono tabular-nums text-muted-foreground">{i + 1}</td>
                    <td className="px-4 py-3">
                      <Link
                        className="font-medium text-primary hover:underline"
                        to={sessionDetailPath({
                          id: s.sessionFk,
                          sessionId: s.sessionId,
                          agentName: s.agentName,
                          namespace: s.namespace,
                        })}
                      >
                        {s.sessionId}
                      </Link>
                      <div className="text-sm text-muted-foreground">{s.agentName}</div>
                    </td>
                    <td className="px-4 py-3 font-mono tabular-nums text-muted-foreground">
                      {s.turnIndex != null ? `#${s.turnIndex}` : '—'}
                    </td>
                    <td className="px-4 py-3 font-mono tabular-nums">{formatDuration(t, locale, s.durationMs)}</td>
                    <td className="px-4 py-3">
                      <Badge tone={phaseTone(s.phase)}>{statusLabel(t, s.phase)}</Badge>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

/** @deprecated Prefer TopAgentsByTokensTable */
export function TopAgentsTable({ agents = [] }: { agents?: AgentUsage[] }) {
  return <TopAgentsByTokensTable agents={agents} />;
}
