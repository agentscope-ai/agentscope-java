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

import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fallbackApiError, serverApiError } from '@/api/errors';
import { I18nProvider, LOCALE_STORAGE_KEY } from '@/i18n';
import { CompressButton } from './CompressButton';

describe('CompressButton API errors', () => {
  beforeEach(() => {
    window.localStorage.clear();
    window.localStorage.setItem(LOCALE_STORAGE_KEY, 'zh');
  });

  it('uses the localized fallback and retains status', async () => {
    const onCompress = vi.fn().mockRejectedValue(
      fallbackApiError('Compression failed', 500),
    );
    render(
      <I18nProvider>
        <CompressButton busy={false} onCompress={onCompress} />
      </I18nProvider>,
    );

    fireEvent.click(screen.getByRole('button', { name: '压缩' }));

    expect(await screen.findByText('压缩失败 (500)')).toBeInTheDocument();
  });

  it('retains the structured 409 force-confirm control flow', async () => {
    const onCompress = vi.fn().mockRejectedValue(serverApiError(
      'session phase unknown',
      409,
      { code: 'busy', hint: 'force_confirm' },
    ));
    render(
      <I18nProvider>
        <CompressButton busy={false} onCompress={onCompress} />
      </I18nProvider>,
    );

    fireEvent.click(screen.getByRole('button', { name: '压缩' }));

    expect(await screen.findByText('确认压缩')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '强制压缩' })).toBeInTheDocument();
  });
});
