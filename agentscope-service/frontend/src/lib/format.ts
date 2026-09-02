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

export function formatNumber(n?: number | null, locale?: string): string {
  if (n == null || Number.isNaN(n)) return '—';
  return new Intl.NumberFormat(locale).format(n);
}

export function formatPercent(ratio?: number | null, locale?: string): string {
  if (ratio == null || Number.isNaN(ratio)) return '—';
  return new Intl.NumberFormat(locale, {
    style: 'percent',
    maximumFractionDigits: 0,
  }).format(Math.max(0, Math.min(1, ratio)));
}

export function formatRelative(iso?: string | null, locale?: string): string {
  if (!iso) return '—';
  const timestamp = Date.parse(iso);
  if (Number.isNaN(timestamp)) return iso;

  const elapsedSeconds = Math.round((Date.now() - timestamp) / 1000);
  const relative = new Intl.RelativeTimeFormat(locale, { numeric: 'always' });
  const absoluteSeconds = Math.abs(elapsedSeconds);
  if (absoluteSeconds < 60) return relative.format(-elapsedSeconds, 'second');

  const elapsedMinutes = Math.round(elapsedSeconds / 60);
  if (Math.abs(elapsedMinutes) < 60) return relative.format(-elapsedMinutes, 'minute');

  const elapsedHours = Math.round(elapsedMinutes / 60);
  if (Math.abs(elapsedHours) < 48) return relative.format(-elapsedHours, 'hour');
  return new Date(timestamp).toLocaleString(locale);
}
