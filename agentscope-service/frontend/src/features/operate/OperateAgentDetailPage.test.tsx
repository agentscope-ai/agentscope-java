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
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { I18nProvider } from '@/i18n';
import {
  fetchAgentMetrics,
  fetchDataPlanes,
  fetchManagedAgent,
  fetchRuntimeSessions,
} from './api';
import OperateAgentDetailPage from './OperateAgentDetailPage';

vi.mock('./api', () => ({
  fetchAgentMetrics: vi.fn(),
  fetchAgentSubagents: vi.fn(),
  fetchAgentWorkspaces: vi.fn(),
  fetchDataPlanes: vi.fn(),
  fetchManagedAgent: vi.fn(),
  fetchRuntimeSessions: vi.fn(),
  phaseTone: vi.fn(() => 'default'),
  sessionDetailPath: vi.fn(() => '/operate/sessions/session-1'),
}));

const fetchAgentMetricsMock = vi.mocked(fetchAgentMetrics);
const fetchDataPlanesMock = vi.mocked(fetchDataPlanes);
const fetchManagedAgentMock = vi.mocked(fetchManagedAgent);
const fetchRuntimeSessionsMock = vi.mocked(fetchRuntimeSessions);

describe('OperateAgentDetailPage metrics window', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] });
    vi.setSystemTime(new Date('2026-08-26T00:00:00.000Z'));
    fetchAgentMetricsMock.mockResolvedValue({ metrics: [] });
    fetchDataPlanesMock.mockResolvedValue({ dataplanes: [] });
    fetchManagedAgentMock.mockResolvedValue({
      name: 'alpha-agent',
      namespace: 'default',
    });
    fetchRuntimeSessionsMock.mockResolvedValue({ sessions: [] });
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('computes the last-24-hours boundary when Usage is requested', async () => {
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false, staleTime: Number.POSITIVE_INFINITY },
      },
    });

    render(
      <I18nProvider>
        <QueryClientProvider client={queryClient}>
          <MemoryRouter initialEntries={['/operate/agents/alpha-agent']}>
            <OperateAgentDetailPage name="alpha-agent" />
          </MemoryRouter>
        </QueryClientProvider>
      </I18nProvider>,
    );

    await screen.findByRole('button', { name: 'Usage' });
    vi.setSystemTime(new Date('2026-08-26T02:00:00.000Z'));
    fireEvent.click(screen.getByRole('button', { name: 'Usage' }));

    await waitFor(() => {
      expect(fetchAgentMetricsMock).toHaveBeenCalledWith({
        agent: 'alpha-agent',
        namespace: 'default',
        since: '2026-08-25T02:00:00.000Z',
      });
    });
  });
});
