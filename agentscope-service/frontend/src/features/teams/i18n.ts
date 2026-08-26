/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

import type { Locale, TranslationFunction, TranslationKey } from '@/i18n';

const teamPhaseKeys: Record<string, TranslationKey> = {
  Pending: 'status.pending',
  Running: 'status.running',
  Idle: 'status.idle',
  Completed: 'teams.status.completed',
  Failed: 'status.failed',
};

const memberPhaseKeys: Record<string, TranslationKey> = {
  Joining: 'teams.status.joining',
  Working: 'teams.status.working',
  Idle: 'status.idle',
  Lost: 'teams.status.lost',
  Failed: 'status.failed',
  Shutdown: 'teams.status.shutdown',
};

const planStatusKeys: Record<string, TranslationKey> = {
  pending: 'status.pending',
  approved: 'teams.status.approved',
  rejected: 'teams.status.rejected',
};

const deployModeKeys: Record<string, TranslationKey> = {
  managed: 'teams.deploy.managed',
  byo: 'teams.deploy.byo',
};

function mappedLabel(
  t: TranslationFunction,
  value: string | undefined,
  keys: Record<string, TranslationKey>,
) {
  if (!value) return t('status.unknown');
  const key = keys[value];
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
