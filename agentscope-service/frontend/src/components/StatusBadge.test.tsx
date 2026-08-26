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

import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { I18nProvider, LOCALE_STORAGE_KEY } from '@/i18n';
import { StatusBadge } from './StatusBadge';

describe('StatusBadge translations', () => {
  beforeEach(() => {
    window.localStorage.clear();
    window.localStorage.setItem(LOCALE_STORAGE_KEY, 'zh');
  });

  it('translates a known machine status', () => {
    render(
      <I18nProvider>
        <StatusBadge status="healthy" />
      </I18nProvider>,
    );

    expect(screen.getByText('健康')).toBeInTheDocument();
  });

  it('preserves a prototype-named machine status', () => {
    render(
      <I18nProvider>
        <StatusBadge status="constructor" />
      </I18nProvider>,
    );

    expect(screen.getByText('constructor')).toBeInTheDocument();
  });
});
