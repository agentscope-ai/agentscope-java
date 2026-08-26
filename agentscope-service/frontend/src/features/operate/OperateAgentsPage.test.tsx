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

import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  I18nProvider,
  LOCALE_STORAGE_KEY,
  useI18n,
} from '@/i18n';
import { fetchManagedAgents } from './api';
import OperateAgentsPage from './OperateAgentsPage';

vi.mock('./api', () => ({
  fetchManagedAgents: vi.fn(),
}));

const fetchManagedAgentsMock = vi.mocked(fetchManagedAgents);

function LocaleSwitch() {
  const { setLocale } = useI18n();
  return (
    <button type="button" onClick={() => setLocale('zh')}>
      switch locale
    </button>
  );
}

describe('OperateAgentsPage locale switching', () => {
  beforeEach(() => {
    window.localStorage.clear();
    window.localStorage.setItem(LOCALE_STORAGE_KEY, 'en');
    fetchManagedAgentsMock.mockResolvedValue({
      items: [
        {
          name: 'alpha-agent',
          displayName: 'Alpha Agent',
          namespace: 'default',
          runtime: 'java',
          presence: 'live',
          activeSessions: 2,
          healthyCount: 1,
          instanceCount: 1,
        },
        {
          name: 'beta-agent',
          displayName: 'Beta Agent',
          namespace: 'default',
          runtime: 'python',
          presence: 'live',
          activeSessions: 0,
          healthyCount: 1,
          instanceCount: 1,
        },
      ],
    });
  });

  it('updates labels without refetching or resetting the search filter', async () => {
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false, staleTime: Number.POSITIVE_INFINITY },
      },
    });

    render(
      <I18nProvider>
        <QueryClientProvider client={queryClient}>
          <MemoryRouter initialEntries={['/operate/agents']}>
            <LocaleSwitch />
            <OperateAgentsPage />
          </MemoryRouter>
        </QueryClientProvider>
      </I18nProvider>,
    );

    await screen.findByText('Alpha Agent');
    const input = screen.getByPlaceholderText(
      'Search agents by name, namespace, or runtime…',
    );
    fireEvent.change(input, { target: { value: 'alpha' } });

    expect(screen.queryByText('Beta Agent')).not.toBeInTheDocument();
    const callsBeforeSwitch = fetchManagedAgentsMock.mock.calls.length;

    fireEvent.click(screen.getByRole('button', { name: 'switch locale' }));

    await waitFor(() => {
      expect(screen.getByRole('heading', { name: '智能体', level: 1 })).toBeInTheDocument();
    });
    expect(input).toHaveValue('alpha');
    expect(screen.queryByText('Beta Agent')).not.toBeInTheDocument();
    expect(fetchManagedAgentsMock).toHaveBeenCalledTimes(callsBeforeSwitch);
  });
});
