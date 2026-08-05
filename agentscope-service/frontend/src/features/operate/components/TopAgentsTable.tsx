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
import type { AgentUsage, SessionDurationRank, SessionUsage } from '../api';
import { phaseTone, sessionDetailPath } from '../api';

function formatDuration(ms?: number) {
  if (ms == null || ms < 0) return '—';
  const sec = Math.floor(ms / 1000);
  if (sec < 60) return `${sec}s`;
  const min = Math.floor(sec / 60);
  if (min < 60) return `${min}m ${sec % 60}s`;
  const hr = Math.floor(min / 60);
  return `${hr}h ${min % 60}m`;
}

export function TopAgentsByTokensTable({ agents = [] }: { agents?: AgentUsage[] }) {
  return (
    <Card className="h-full">
      <CardHeader>
        <CardTitle>Top 10 agents by tokens</CardTitle>
        <CardDescription>Ranked by token usage deltas · last 24h</CardDescription>
      </CardHeader>
      <CardContent>
        {agents.length === 0 ? (
          <p className="text-sm text-muted-foreground">No agent usage yet.</p>
        ) : (
          <div className="max-h-80 overflow-auto rounded-lg border border-border">
            <table className="w-full text-left text-sm">
              <thead className="sticky top-0 border-b border-border bg-muted/50 text-[13px] uppercase tracking-wide text-muted-foreground">
                <tr>
                  <th className="px-4 py-3 font-medium">#</th>
                  <th className="px-4 py-3 font-medium">Agent</th>
                  <th className="px-4 py-3 font-medium">Tokens</th>
                  <th className="px-4 py-3 font-medium">Active</th>
                  <th className="px-4 py-3 font-medium">Errors</th>
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
                    <td className="px-4 py-3 font-mono tabular-nums">{(a.totalTokens || 0).toLocaleString()}</td>
                    <td className="px-4 py-3 font-mono tabular-nums">{a.activeSessions ?? 0}</td>
                    <td className="px-4 py-3 font-mono tabular-nums text-muted-foreground">{a.errorCount ?? 0}</td>
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
  return (
    <Card className="h-full">
      <CardHeader>
        <CardTitle>Top 10 agents by active sessions</CardTitle>
        <CardDescription>Ranked by peak concurrent sessions · last 5 minutes</CardDescription>
      </CardHeader>
      <CardContent>
        {agents.length === 0 ? (
          <p className="text-sm text-muted-foreground">No active-session samples yet.</p>
        ) : (
          <div className="max-h-80 overflow-auto rounded-lg border border-border">
            <table className="w-full text-left text-sm">
              <thead className="sticky top-0 border-b border-border bg-muted/50 text-[13px] uppercase tracking-wide text-muted-foreground">
                <tr>
                  <th className="px-4 py-3 font-medium">#</th>
                  <th className="px-4 py-3 font-medium">Agent</th>
                  <th className="px-4 py-3 font-medium">Peak active</th>
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
                    <td className="px-4 py-3 font-mono tabular-nums">{a.activeSessions ?? 0}</td>
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
  return (
    <Card className="h-full">
      <CardHeader>
        <CardTitle>Top 10 sessions by tokens</CardTitle>
        <CardDescription>Ranked by token usage deltas · last 24h</CardDescription>
      </CardHeader>
      <CardContent>
        {sessions.length === 0 ? (
          <p className="text-sm text-muted-foreground">No session usage yet.</p>
        ) : (
          <div className="max-h-80 overflow-auto rounded-lg border border-border">
            <table className="w-full text-left text-sm">
              <thead className="sticky top-0 border-b border-border bg-muted/50 text-[13px] uppercase tracking-wide text-muted-foreground">
                <tr>
                  <th className="px-4 py-3 font-medium">#</th>
                  <th className="px-4 py-3 font-medium">Session</th>
                  <th className="px-4 py-3 font-medium">Tokens</th>
                  <th className="px-4 py-3 font-medium">Phase</th>
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
                    <td className="px-4 py-3 font-mono tabular-nums">{(s.totalTokens || 0).toLocaleString()}</td>
                    <td className="px-4 py-3">
                      <Badge tone={phaseTone(s.phase)}>{s.phase || '—'}</Badge>
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
  return (
    <Card className="h-full">
      <CardHeader>
        <CardTitle>Top 10 active turns by duration</CardTitle>
        <CardDescription>
          Only phase=active · ranked by current turn elapsed (not session lifetime)
        </CardDescription>
      </CardHeader>
      <CardContent>
        {sessions.length === 0 ? (
          <p className="text-sm text-muted-foreground">No active turns right now.</p>
        ) : (
          <div className="max-h-80 overflow-auto rounded-lg border border-border">
            <table className="w-full text-left text-sm">
              <thead className="sticky top-0 border-b border-border bg-muted/50 text-[13px] uppercase tracking-wide text-muted-foreground">
                <tr>
                  <th className="px-4 py-3 font-medium">#</th>
                  <th className="px-4 py-3 font-medium">Session</th>
                  <th className="px-4 py-3 font-medium">Turn</th>
                  <th className="px-4 py-3 font-medium">Elapsed</th>
                  <th className="px-4 py-3 font-medium">Phase</th>
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
                    <td className="px-4 py-3 font-mono tabular-nums">{formatDuration(s.durationMs)}</td>
                    <td className="px-4 py-3">
                      <Badge tone={phaseTone(s.phase)}>{s.phase || '—'}</Badge>
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
