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

import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { I18nProvider, LOCALE_STORAGE_KEY } from '@/i18n';
import SessionTranscript from './SessionTranscript';

const sessionApi = vi.hoisted(() => ({
  archiveManagedSession: vi.fn(),
  deleteManagedSession: vi.fn(),
  getManagedSession: vi.fn(),
  restoreManagedSession: vi.fn(),
  updateManagedSession: vi.fn(),
}));
const environmentApi = vi.hoisted(() => ({ listEnvironments: vi.fn() }));
const memoryApi = vi.hoisted(() => ({ listMemoryStores: vi.fn() }));
const vaultApi = vi.hoisted(() => ({ listVaults: vi.fn() }));

vi.mock('../api/managedSessions', () => sessionApi);
vi.mock('../api/environments', () => environmentApi);
vi.mock('../api/memoryStores', () => memoryApi);
vi.mock('../api/vaults', () => vaultApi);
vi.mock('./SessionEventTimeline', () => ({ default: () => null }));

describe('SessionTranscript local validation', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.localStorage.clear();
    window.localStorage.setItem(LOCALE_STORAGE_KEY, 'zh');
    sessionApi.getManagedSession.mockResolvedValue({
      id: 'session-1',
      agentId: 'agent-1',
      environmentId: 'env-1',
      status: 'created',
      createdAt: 1,
      updatedAt: 1,
    });
    environmentApi.listEnvironments.mockResolvedValue([
      { id: 'env-1', name: 'Environment One', type: 'local', createdAt: 1, updatedAt: 1 },
    ]);
    memoryApi.listMemoryStores.mockResolvedValue([]);
    vaultApi.listVaults.mockResolvedValue([]);
  });

  it('shows precise override and mount validation messages without updating', async () => {
    render(
      <MemoryRouter>
        <I18nProvider>
          <SessionTranscript agentId="agent-1" sessionId="session-1" embedded />
        </I18nProvider>
      </MemoryRouter>,
    );
    const environment = screen.getByRole('combobox');
    await waitFor(() => expect(environment).toHaveValue('env-1'));

    fireEvent.change(screen.getByPlaceholderText('例如 10'), {
      target: { value: 'not-a-number' },
    });
    fireEvent.click(screen.getByRole('button', { name: '保存覆盖项' }));
    expect(screen.getByText('maxIters 必须是数字')).toBeInTheDocument();

    fireEvent.change(environment, { target: { value: '' } });
    fireEvent.click(screen.getByRole('button', { name: '保存挂载' }));
    expect(screen.getByText('environmentId 为必填项')).toBeInTheDocument();
    expect(sessionApi.updateManagedSession).not.toHaveBeenCalled();
  });
});
