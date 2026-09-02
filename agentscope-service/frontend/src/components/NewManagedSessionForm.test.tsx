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
import { fallbackApiError } from '@/api/errors';
import { I18nProvider, LOCALE_STORAGE_KEY } from '@/i18n';
import NewManagedSessionForm from './NewManagedSessionForm';

const agentApi = vi.hoisted(() => ({ getAgent: vi.fn() }));
const environmentApi = vi.hoisted(() => ({
  ensureDefaultEnvironment: vi.fn(),
  listEnvironments: vi.fn(),
}));
const fileApi = vi.hoisted(() => ({ listFiles: vi.fn() }));
const memoryApi = vi.hoisted(() => ({ listMemoryStores: vi.fn() }));
const sessionApi = vi.hoisted(() => ({ createManagedSession: vi.fn() }));
const vaultApi = vi.hoisted(() => ({ listVaults: vi.fn() }));

vi.mock('../api/agents', () => agentApi);
vi.mock('../api/environments', () => environmentApi);
vi.mock('../api/files', () => fileApi);
vi.mock('../api/memoryStores', () => memoryApi);
vi.mock('../api/managedSessions', () => sessionApi);
vi.mock('../api/vaults', () => vaultApi);

describe('NewManagedSessionForm errors', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.localStorage.clear();
    window.localStorage.setItem(LOCALE_STORAGE_KEY, 'zh');
    agentApi.getAgent.mockResolvedValue({
      id: 'agent-1',
      name: 'Agent One',
      scope: 'user',
      createdAt: 1,
      updatedAt: 1,
      defaultEnvironmentId: 'env-1',
    });
    environmentApi.listEnvironments.mockResolvedValue([
      { id: 'env-1', name: 'Environment One', type: 'local', createdAt: 1, updatedAt: 1 },
    ]);
    environmentApi.ensureDefaultEnvironment.mockResolvedValue({ id: 'env-default' });
    fileApi.listFiles.mockResolvedValue([]);
    memoryApi.listMemoryStores.mockResolvedValue([]);
    vaultApi.listVaults.mockResolvedValue([]);
  });

  function renderForm() {
    render(
      <I18nProvider>
        <NewManagedSessionForm
          agentId="agent-1"
          modal={false}
          onCreated={vi.fn()}
        />
      </I18nProvider>,
    );
  }

  it('shows the precise maxIters validation message before making an API call', async () => {
    renderForm();
    const submit = screen.getByRole('button', { name: '创建会话' });
    await waitFor(() => expect(submit).toBeEnabled());

    fireEvent.change(screen.getByPlaceholderText('例如 10'), {
      target: { value: 'not-a-number' },
    });
    fireEvent.click(submit);

    expect(screen.getByText('maxIters 必须是数字')).toBeInTheDocument();
    expect(sessionApi.createManagedSession).not.toHaveBeenCalled();
    expect(environmentApi.ensureDefaultEnvironment).not.toHaveBeenCalled();
  });

  it('localizes fallback API failures while retaining the status', async () => {
    sessionApi.createManagedSession.mockRejectedValue(
      fallbackApiError('Failed to create session', 503),
    );
    renderForm();
    const submit = screen.getByRole('button', { name: '创建会话' });
    await waitFor(() => expect(submit).toBeEnabled());

    fireEvent.click(submit);

    expect(await screen.findByText('会话创建失败 (503)')).toBeInTheDocument();
    expect(screen.queryByText('Failed to create session (503)')).not.toBeInTheDocument();
  });
});
