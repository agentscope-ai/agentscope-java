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

import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { PressureGauge } from '@/components/PressureGauge';
import { phaseHint, phaseTone, type RuntimeSession } from '../api';

function formatTime(v?: string) {
  if (!v) return '—';
  try {
    return new Date(v).toLocaleString();
  } catch {
    return v;
  }
}

export function StatusStrip({ session }: { session?: RuntimeSession }) {
  const healthy = session?.instanceHealthy;
  const hint = phaseHint(session?.phase);
  const instanceId = session?.instanceRef;
  const instanceUrl = session?.instanceBaseUrl;
  return (
    <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-6">
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-sm font-medium text-muted-foreground">Phase</CardTitle>
        </CardHeader>
        <CardContent className="space-y-1.5">
          <Badge tone={phaseTone(session?.phase)}>{session?.phase || '—'}</Badge>
          {hint ? <p className="text-xs text-muted-foreground">{hint}</p> : null}
        </CardContent>
      </Card>
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-sm font-medium text-muted-foreground">Model</CardTitle>
        </CardHeader>
        <CardContent className="truncate text-sm text-foreground">
          {session?.model || '—'}
        </CardContent>
      </Card>
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-sm font-medium text-muted-foreground">Pressure</CardTitle>
        </CardHeader>
        <CardContent>
          <PressureGauge value={session?.snapshot?.contextPressure} />
        </CardContent>
      </Card>
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-sm font-medium text-muted-foreground">Lifetime usage</CardTitle>
        </CardHeader>
        <CardContent className="space-y-1">
          <div className="font-mono text-sm tabular-nums text-foreground">
            {(session?.snapshot?.totalTokens ?? 0).toLocaleString()}
          </div>
          <p className="text-xs text-muted-foreground">
            Σ prompt+completion across turns — not the current context window
            {session?.snapshot?.promptTokens != null || session?.snapshot?.completionTokens != null
              ? ` · in ${(session?.snapshot?.promptTokens ?? 0).toLocaleString()} / out ${(session?.snapshot?.completionTokens ?? 0).toLocaleString()}`
              : ''}
          </p>
        </CardContent>
      </Card>
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-sm font-medium text-muted-foreground">Last active</CardTitle>
        </CardHeader>
        <CardContent className="text-sm text-muted-foreground">
          {formatTime(session?.lastActiveAt)}
        </CardContent>
      </Card>
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-sm font-medium text-muted-foreground">Instance</CardTitle>
        </CardHeader>
        <CardContent className="space-y-1.5">
          <div>
            {healthy === true ? (
              <Badge tone="success">healthy</Badge>
            ) : healthy === false ? (
              <Badge tone="danger">unhealthy</Badge>
            ) : (
              <Badge>unknown</Badge>
            )}
          </div>
          {instanceId || instanceUrl ? (
            <div className="min-w-0 space-y-0.5">
              {instanceId ? (
                <p className="truncate font-mono text-xs text-foreground" title={instanceId}>
                  {instanceId}
                </p>
              ) : null}
              {instanceUrl ? (
                <p className="truncate font-mono text-xs text-muted-foreground" title={instanceUrl}>
                  {instanceUrl}
                </p>
              ) : null}
            </div>
          ) : (
            <p className="text-xs text-muted-foreground">No instance bound</p>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
