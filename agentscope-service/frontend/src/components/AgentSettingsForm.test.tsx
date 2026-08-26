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
import { MemoryRouter } from 'react-router-dom';
import type { AgentDefinition } from '../api/agents';
import { I18nProvider, LOCALE_STORAGE_KEY } from '@/i18n';
import AgentSettingsForm from './AgentSettingsForm';

const agentApi = vi.hoisted(() => ({
  archiveAgent: vi.fn(),
  deleteAgent: vi.fn(),
  getAgent: vi.fn(),
  listVersions: vi.fn(),
  updateAgent: vi.fn(),
}));

const workspaceApi = vi.hoisted(() => ({
  getWorkspace: vi.fn(),
  listWorkspaces: vi.fn(),
}));

const environmentApi = vi.hoisted(() => ({ listEnvironments: vi.fn() }));
const vaultApi = vi.hoisted(() => ({ listVaults: vi.fn() }));
const memoryApi = vi.hoisted(() => ({ listMemoryStores: vi.fn() }));

vi.mock('../api/agents', () => agentApi);
vi.mock('../api/workspaces', () => workspaceApi);
vi.mock('../api/environments', () => environmentApi);
vi.mock('../api/vaults', () => vaultApi);
vi.mock('../api/memoryStores', () => memoryApi);
vi.mock('../lib/auth', () => ({ getUsername: () => 'owner-1' }));

describe('AgentSettingsForm local validation', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.localStorage.clear();
    window.localStorage.setItem(LOCALE_STORAGE_KEY, 'zh');
    agentApi.listVersions.mockResolvedValue([]);
    workspaceApi.listWorkspaces.mockResolvedValue([]);
    environmentApi.listEnvironments.mockResolvedValue([]);
    vaultApi.listVaults.mockResolvedValue([]);
    memoryApi.listMemoryStores.mockResolvedValue([]);
  });

  it('shows the precise localized missing-version error without calling the API', () => {
    const agent: AgentDefinition = {
      id: 'agent-1',
      name: 'Agent One',
      scope: 'user',
      ownerId: 'owner-1',
      createdAt: 1,
      updatedAt: 1,
    };

    render(
      <MemoryRouter>
        <I18nProvider>
          <AgentSettingsForm agent={agent} />
        </I18nProvider>
      </MemoryRouter>,
    );

    fireEvent.click(screen.getByRole('button', { name: '保存更改' }));

    expect(screen.getByText('缺少用于乐观锁的智能体版本')).toBeInTheDocument();
    expect(agentApi.updateAgent).not.toHaveBeenCalled();
  });
});
