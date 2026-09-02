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

import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { useI18n } from '@/i18n';
import type { TokenBucket } from '../api';
import { formatClock, formatNumber } from '../i18n';

const CHART_H = 128;

export function TokenTrend({
  points = [],
  loading,
  error,
}: {
  points?: TokenBucket[];
  loading?: boolean;
  error?: boolean;
}) {
  const { locale, t } = useI18n();
  const max = Math.max(1, ...points.map((p) => p.totalTokens || 0));
  const showLabels = points.length > 0 && points.length <= 24;

  return (
    <Card>
      <CardHeader>
        <CardTitle>{t('operate.tokenTrend.title')}</CardTitle>
        <CardDescription>{t('operate.tokenTrend.description')}</CardDescription>
      </CardHeader>
      <CardContent>
        {error ? (
          <p className="text-sm text-muted-foreground">{t('operate.tokenTrend.unavailable')}</p>
        ) : loading && points.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t('common.loading')}</p>
        ) : points.length === 0 ? (
          <p className="text-sm text-muted-foreground">{t('operate.tokenTrend.empty')}</p>
        ) : (
          <div className="space-y-2">
            <div className="flex items-end gap-1.5" style={{ height: CHART_H }}>
              {points.map((p, i) => {
                const hPx = Math.max(2, Math.round(((p.totalTokens || 0) / max) * CHART_H));
                const label = p.bucketStart
                  ? formatClock(locale, p.bucketStart)
                  : `#${i}`;
                return (
                  <div
                    key={p.bucketStart || i}
                    className="min-w-0 flex-1 rounded-t bg-indigo-400/80 transition-all"
                    style={{ height: hPx }}
                    title={t('operate.tokenTrend.tooltip', {
                      time: label,
                      tokens: formatNumber(locale, p.totalTokens || 0),
                    })}
                  />
                );
              })}
            </div>
            {showLabels && (
              <div className="flex gap-1.5">
                {points.map((p, i) => {
                  const label = p.bucketStart
                    ? formatClock(locale, p.bucketStart)
                    : `#${i}`;
                  return (
                    <span
                      key={`lbl-${p.bucketStart || i}`}
                      className="min-w-0 flex-1 truncate text-center text-[11px] text-muted-foreground"
                    >
                      {label}
                    </span>
                  );
                })}
              </div>
            )}
          </div>
        )}
      </CardContent>
    </Card>
  );
}
