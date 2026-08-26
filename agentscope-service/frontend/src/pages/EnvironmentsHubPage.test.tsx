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
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fallbackApiError } from '@/api/errors';
import { I18nProvider, LOCALE_STORAGE_KEY } from '@/i18n';
import EnvironmentsHubPage from './EnvironmentsHubPage';

const environmentApi = vi.hoisted(() => ({
  archiveEnvironment: vi.fn(),
  createEnvironment: vi.fn(),
  deleteEnvironment: vi.fn(),
  listEnvironments: vi.fn(),
  updateEnvironment: vi.fn(),
}));

const handsApi = vi.hoisted(() => ({
  fetchHandsStatus: vi.fn(),
}));

vi.mock('../api/environments', () => environmentApi);
vi.mock('../api/hands', () => handsApi);

describe('EnvironmentsHubPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.localStorage.clear();
    environmentApi.listEnvironments.mockResolvedValue([
      {
        id: 'environment-1',
        name: 'Environment One',
        type: 'constructor',
        createdAt: 1,
        updatedAt: 1,
      },
    ]);
    handsApi.fetchHandsStatus.mockRejectedValue(new Error('unavailable'));
  });

  it('preserves a prototype-named environment type returned by the server', async () => {
    render(
      <I18nProvider>
        <EnvironmentsHubPage />
      </I18nProvider>,
    );

    expect(await screen.findByText('constructor')).toBeInTheDocument();
  });

  it('localizes a bodyless HTTP fallback error in Chinese', async () => {
    window.localStorage.setItem(LOCALE_STORAGE_KEY, 'zh');
    environmentApi.listEnvironments.mockRejectedValueOnce(
      fallbackApiError('Failed to load environments', 503),
    );

    render(
      <I18nProvider>
        <EnvironmentsHubPage />
      </I18nProvider>,
    );

    expect(await screen.findByText('加载失败。 (503)')).toBeInTheDocument();
    expect(screen.queryByText('Failed to load environments')).not.toBeInTheDocument();
  });
});
