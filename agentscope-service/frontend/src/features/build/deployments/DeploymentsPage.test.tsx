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
import { readApiError } from '@/api/errors';
import { I18nProvider, LOCALE_STORAGE_KEY } from '@/i18n';
import DeploymentsPage from './DeploymentsPage';

const deploymentApi = vi.hoisted(() => ({
  archiveDeployment: vi.fn(),
  createDeployment: vi.fn(),
  deleteDeployment: vi.fn(),
  listDeployments: vi.fn(),
  runDeployment: vi.fn(),
  updateDeployment: vi.fn(),
}));

const agentApi = vi.hoisted(() => ({
  listAgents: vi.fn(),
}));

const environmentApi = vi.hoisted(() => ({
  listEnvironments: vi.fn(),
}));

vi.mock('../../../api/deployments', () => deploymentApi);
vi.mock('../../../api/agents', () => agentApi);
vi.mock('../../../api/environments', () => environmentApi);

describe('DeploymentsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.localStorage.clear();
    deploymentApi.listDeployments.mockResolvedValue([
      {
        id: 'deployment-1',
        name: 'Future deployment',
        agentId: 'agent-1',
        environmentId: 'environment-1',
        triggerType: 'constructor',
        enabled: true,
        createdAt: 1,
        updatedAt: 1,
      },
    ]);
    agentApi.listAgents.mockResolvedValue([]);
    environmentApi.listEnvironments.mockResolvedValue([]);
  });

  it('preserves a prototype-named trigger type returned by the server', async () => {
    render(
      <I18nProvider>
        <DeploymentsPage />
      </I18nProvider>,
    );

    expect(await screen.findByText('constructor')).toBeInTheDocument();
  });

  it('localizes a bodyless API fallback in Chinese while retaining its status', async () => {
    window.localStorage.setItem(LOCALE_STORAGE_KEY, 'zh');
    deploymentApi.listDeployments.mockRejectedValue(
      await readApiError(
        new Response(null, { status: 503 }),
        'Failed to load deployments',
      ),
    );

    render(
      <I18nProvider>
        <DeploymentsPage />
      </I18nProvider>,
    );

    expect(await screen.findByText('加载失败。 (503)')).toBeInTheDocument();
    expect(screen.queryByText(/Failed to load deployments/)).not.toBeInTheDocument();
  });
});
