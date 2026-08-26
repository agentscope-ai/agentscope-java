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

import type { Locale, TranslationFunction, TranslationKey } from '@/i18n';

const teamPhaseKeys = new Map<string, TranslationKey>([
  ['Pending', 'status.pending'],
  ['Running', 'status.running'],
  ['Idle', 'status.idle'],
  ['Completed', 'teams.status.completed'],
  ['Failed', 'status.failed'],
]);

const memberPhaseKeys = new Map<string, TranslationKey>([
  ['Joining', 'teams.status.joining'],
  ['Working', 'teams.status.working'],
  ['Idle', 'status.idle'],
  ['Lost', 'teams.status.lost'],
  ['Failed', 'status.failed'],
  ['Shutdown', 'teams.status.shutdown'],
]);

const planStatusKeys = new Map<string, TranslationKey>([
  ['pending', 'status.pending'],
  ['approved', 'teams.status.approved'],
  ['rejected', 'teams.status.rejected'],
]);

const deployModeKeys = new Map<string, TranslationKey>([
  ['managed', 'teams.deploy.managed'],
  ['byo', 'teams.deploy.byo'],
]);

function mappedLabel(
  t: TranslationFunction,
  value: string | undefined,
  keys: ReadonlyMap<string, TranslationKey>,
) {
  if (!value) return t('status.unknown');
  const key = keys.get(value);
  return key ? t(key) : value;
}

export function teamPhaseLabel(t: TranslationFunction, value: string | undefined) {
  return mappedLabel(t, value, teamPhaseKeys);
}

export function memberPhaseLabel(t: TranslationFunction, value: string | undefined) {
  return mappedLabel(t, value, memberPhaseKeys);
}

export function planStatusLabel(t: TranslationFunction, value: string | undefined) {
  return value ? mappedLabel(t, value, planStatusKeys) : '—';
}

export function deployModeLabel(t: TranslationFunction, value: string | undefined) {
  return mappedLabel(t, value || 'byo', deployModeKeys);
}

export function formatTeamDate(locale: Locale, value: string | undefined) {
  if (!value) return '';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat(locale === 'zh' ? 'zh-CN' : 'en-US', {
    dateStyle: 'medium',
    timeStyle: 'medium',
  }).format(date);
}

export function formatTeamNumber(locale: Locale, value: number) {
  return new Intl.NumberFormat(locale === 'zh' ? 'zh-CN' : 'en-US').format(value);
}
