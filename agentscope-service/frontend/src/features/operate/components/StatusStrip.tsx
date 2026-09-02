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
import { useI18n } from '@/i18n';
import { phaseTone, type RuntimeSession } from '../api';
import { formatDateTime, formatNumber, phaseHintLabel, statusLabel } from '../i18n';

export function StatusStrip({ session }: { session?: RuntimeSession }) {
  const { locale, t } = useI18n();
  const healthy = session?.instanceHealthy;
  const hint = phaseHintLabel(t, session?.phase);
  const instanceId = session?.instanceRef;
  const instanceUrl = session?.instanceBaseUrl;
  return (
    <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-6">
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-sm font-medium text-muted-foreground">{t('operate.fields.phase')}</CardTitle>
        </CardHeader>
        <CardContent className="space-y-1.5">
          <Badge tone={phaseTone(session?.phase)}>{statusLabel(t, session?.phase)}</Badge>
          {hint ? <p className="text-xs text-muted-foreground">{hint}</p> : null}
        </CardContent>
      </Card>
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-sm font-medium text-muted-foreground">{t('common.fields.model')}</CardTitle>
        </CardHeader>
        <CardContent className="truncate text-sm text-foreground">
          {session?.model || '—'}
        </CardContent>
      </Card>
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-sm font-medium text-muted-foreground">{t('operate.fields.pressure')}</CardTitle>
        </CardHeader>
        <CardContent>
          <PressureGauge value={session?.snapshot?.contextPressure} />
        </CardContent>
      </Card>
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-sm font-medium text-muted-foreground">{t('operate.statusStrip.lifetimeUsage')}</CardTitle>
        </CardHeader>
        <CardContent className="space-y-1">
          <div className="font-mono text-sm tabular-nums text-foreground">
            {formatNumber(locale, session?.snapshot?.totalTokens ?? 0)}
          </div>
          <p className="text-xs text-muted-foreground">
            {t('operate.statusStrip.usageDescription')}
            {session?.snapshot?.promptTokens != null || session?.snapshot?.completionTokens != null
              ? ` · ${t('operate.statusStrip.inputOutput', {
                  input: formatNumber(locale, session?.snapshot?.promptTokens ?? 0),
                  output: formatNumber(locale, session?.snapshot?.completionTokens ?? 0),
                })}`
              : ''}
          </p>
        </CardContent>
      </Card>
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-sm font-medium text-muted-foreground">{t('operate.statusStrip.lastActive')}</CardTitle>
        </CardHeader>
        <CardContent className="text-sm text-muted-foreground">
          {formatDateTime(locale, session?.lastActiveAt)}
        </CardContent>
      </Card>
      <Card>
        <CardHeader className="pb-3">
          <CardTitle className="text-sm font-medium text-muted-foreground">{t('operate.fields.instance')}</CardTitle>
        </CardHeader>
        <CardContent className="space-y-1.5">
          <div>
            {healthy === true ? (
              <Badge tone="success">{t('status.healthy')}</Badge>
            ) : healthy === false ? (
              <Badge tone="danger">{t('status.unhealthy')}</Badge>
            ) : (
              <Badge>{t('status.unknown')}</Badge>
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
            <p className="text-xs text-muted-foreground">{t('operate.statusStrip.noInstance')}</p>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
