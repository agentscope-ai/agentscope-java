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

import { describe, expect, it } from 'vitest';
import type { TranslationFunction } from '@/i18n';
import { translate } from '@/i18n/translate';
import { formatNumber, statusLabel } from './i18n';

const zhT: TranslationFunction = (key, params) => translate('zh', key, params);

describe('operate i18n helpers', () => {
  it('translates known statuses and preserves unknown machine values', () => {
    expect(statusLabel(zhT, 'running')).toBe('运行中');
    expect(statusLabel(zhT, 'vendor-specific')).toBe('vendor-specific');
    expect(statusLabel(zhT, 'constructor')).toBe('constructor');
  });

  it('formats display numbers using the selected locale', () => {
    expect(formatNumber('en', 12345)).toBe('12,345');
    expect(formatNumber('zh', 12345)).toBe('12,345');
  });
});
