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
import { fallbackApiError, serverApiError } from '@/api/errors';
import type { TranslationFunction } from '@/i18n';
import { translate } from '@/i18n/translate';
import { formatMessagesError } from './useSessionMessages';

const zhT: TranslationFunction = (key, params) => translate('zh', key, params);

describe('formatMessagesError', () => {
  it('preserves backend detail in status-specific guidance', () => {
    const message = formatMessagesError(
      serverApiError('对话记录尚未落盘', 404),
      zhT,
    );

    expect(message).toContain('对话记录尚未落盘');
    expect(message).toContain('数据平面上未找到消息');
  });

  it('localizes empty-body and network fallbacks', () => {
    expect(formatMessagesError(
      fallbackApiError('Request failed', 503),
      zhT,
    )).toContain('消息加载失败 (503)');
    expect(formatMessagesError(new TypeError('Failed to fetch'), zhT)).toBe(
      '消息加载失败',
    );
  });
});
