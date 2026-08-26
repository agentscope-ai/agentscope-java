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

import { afterEach, describe, expect, it, vi } from 'vitest';
import { formatNumber, formatPercent, formatRelative } from './format';

describe('locale-aware formatters', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('formats numbers and percentages with an explicit locale', () => {
    expect(formatNumber(1234567, 'en-US')).toBe('1,234,567');
    expect(formatPercent(0.42, 'en-US')).toBe('42%');
  });

  it('formats relative time in the selected language', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-08-26T08:00:30.000Z'));

    expect(formatRelative('2026-08-26T08:00:00.000Z', 'en-US')).toBe(
      '30 seconds ago',
    );
    expect(formatRelative('2026-08-26T08:00:00.000Z', 'zh-CN')).toMatch(
      /^30秒.*前$/,
    );
  });

  it('keeps missing and invalid values stable', () => {
    expect(formatNumber(null, 'zh-CN')).toBe('—');
    expect(formatPercent(Number.NaN, 'zh-CN')).toBe('—');
    expect(formatRelative('not-a-date', 'zh-CN')).toBe('not-a-date');
  });
});
