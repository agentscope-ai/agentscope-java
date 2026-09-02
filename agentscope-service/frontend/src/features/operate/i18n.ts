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

import type {
  Locale,
  TranslationFunction,
  TranslationKey,
} from '@/i18n';

const STATUS_KEYS = new Map<string, TranslationKey>([
  ['active', 'status.active'],
  ['archived', 'status.archived'],
  ['compressing', 'status.compressing'],
  ['failed', 'status.failed'],
  ['healthy', 'status.healthy'],
  ['idle', 'status.idle'],
  ['pending', 'status.pending'],
  ['ready', 'status.ready'],
  ['running', 'status.running'],
  ['terminated', 'status.terminated'],
  ['unhealthy', 'status.unhealthy'],
  ['unknown', 'status.unknown'],
  ['aborted', 'operate.status.aborted'],
  ['completed', 'operate.status.completed'],
  ['compacted', 'operate.status.compacted'],
  ['historical', 'operate.status.historical'],
  ['inactive', 'operate.status.inactive'],
  ['live', 'operate.status.live'],
  ['offline', 'operate.status.offline'],
  ['stale', 'operate.status.stale'],
]);

export function localeTag(locale: Locale): string {
  return locale === 'zh' ? 'zh-CN' : 'en-US';
}

export function formatNumber(locale: Locale, value: number): string {
  return new Intl.NumberFormat(localeTag(locale)).format(value);
}

export function formatDateTime(locale: Locale, value?: string | number): string {
  if (value == null || value === '') return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return String(value);
  return new Intl.DateTimeFormat(localeTag(locale), {
    dateStyle: 'medium',
    timeStyle: 'medium',
  }).format(date);
}

export function formatClock(locale: Locale, value?: string): string {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat(localeTag(locale), {
    hour: '2-digit',
    minute: '2-digit',
  }).format(date);
}

export function formatDuration(
  t: TranslationFunction,
  locale: Locale,
  milliseconds?: number,
): string {
  if (milliseconds == null || milliseconds < 0) return '—';
  const seconds = Math.floor(milliseconds / 1000);
  if (seconds < 60) {
    return t('operate.duration.seconds', { seconds: formatNumber(locale, seconds) });
  }
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) {
    return t('operate.duration.minutesSeconds', {
      minutes: formatNumber(locale, minutes),
      seconds: formatNumber(locale, seconds % 60),
    });
  }
  const hours = Math.floor(minutes / 60);
  return t('operate.duration.hoursMinutes', {
    hours: formatNumber(locale, hours),
    minutes: formatNumber(locale, minutes % 60),
  });
}

export function statusLabel(
  t: TranslationFunction,
  status?: string | null,
  fallback = '—',
): string {
  if (!status) return fallback;
  const key = STATUS_KEYS.get(status.toLowerCase());
  return key ? t(key) : status;
}

export function phaseHintLabel(
  t: TranslationFunction,
  phase?: string | null,
): string {
  switch ((phase || '').toLowerCase()) {
    case 'active':
      return t('operate.phaseHint.active');
    case 'idle':
      return t('operate.phaseHint.idle');
    case 'compressing':
      return t('operate.phaseHint.compressing');
    case 'archived':
      return t('operate.phaseHint.archived');
    case 'terminated':
      return t('operate.phaseHint.terminated');
    default:
      return '';
  }
}

export function messageRoleLabel(
  t: TranslationFunction,
  role?: string | null,
  fallbackKey: TranslationKey = 'operate.message.message',
): string {
  if (!role) return t(fallbackKey);
  switch (role.toLowerCase()) {
    case 'user':
      return t('message.role.user');
    case 'assistant':
      return t('message.role.assistant');
    case 'system':
      return t('message.role.system');
    case 'error':
      return t('message.role.error');
    case 'tool':
      return t('operate.message.role.tool');
    default:
      return role;
  }
}
