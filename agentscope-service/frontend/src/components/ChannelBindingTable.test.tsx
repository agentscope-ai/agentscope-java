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
import { I18nProvider, LOCALE_STORAGE_KEY } from '@/i18n';
import ChannelBindingTable from './ChannelBindingTable';

const channelApi = vi.hoisted(() => ({
  addBinding: vi.fn(),
  deleteBinding: vi.fn(),
  listAgentBindings: vi.fn(),
  listChannels: vi.fn(),
  setChannelDefault: vi.fn(),
  updateBinding: vi.fn(),
}));

vi.mock('../api/channels', () => channelApi);

describe('ChannelBindingTable translations', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.localStorage.clear();
    window.localStorage.setItem(LOCALE_STORAGE_KEY, 'zh');
    channelApi.listChannels.mockResolvedValue([
      {
        channelId: 'discord',
        defaultAgentId: null,
        dmScope: 'PER_PEER',
        started: true,
      },
    ]);
    channelApi.listAgentBindings.mockResolvedValue([]);
    channelApi.addBinding.mockResolvedValue(undefined);
  });

  it('translates labels while preserving request enum values', async () => {
    render(
      <I18nProvider>
        <ChannelBindingTable agentId="agent-1" />
      </I18nProvider>,
    );

    expect(await screen.findByText('按发送者独立会话')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /添加绑定/ }));

    fireEvent.change(screen.getAllByRole('combobox')[0], {
      target: { value: 'team' },
    });
    fireEvent.change(screen.getByRole('textbox'), {
      target: { value: 'alpha-team' },
    });
    fireEvent.click(screen.getByRole('button', { name: '创建' }));

    await waitFor(() => {
      expect(channelApi.addBinding).toHaveBeenCalledWith('agent-1', {
        channelId: 'discord',
        team: 'alpha-team',
        tier: 'team',
      });
    });
  });

  it('preserves prototype-named enum values returned by the server', async () => {
    channelApi.listChannels.mockResolvedValue([
      {
        channelId: 'discord',
        defaultAgentId: null,
        dmScope: 'constructor',
        started: true,
      },
    ]);
    channelApi.listAgentBindings.mockResolvedValue([
      {
        channelId: 'discord',
        index: 0,
        tier: 'constructor',
        sessionScope: 'constructor',
      },
    ]);

    render(
      <I18nProvider>
        <ChannelBindingTable agentId="agent-1" />
      </I18nProvider>,
    );

    await waitFor(() => {
      expect(screen.getAllByText('constructor')).toHaveLength(4);
    });
  });

  it('groups bindings under a prototype-named channel id', async () => {
    channelApi.listChannels.mockResolvedValue([
      {
        channelId: 'constructor',
        defaultAgentId: null,
        dmScope: 'PER_PEER',
        started: true,
      },
    ]);
    channelApi.listAgentBindings.mockResolvedValue([
      {
        channelId: 'constructor',
        index: 0,
        tier: 'peer',
        peer: 'peer-1',
      },
    ]);

    render(
      <I18nProvider>
        <ChannelBindingTable agentId="agent-1" />
      </I18nProvider>,
    );

    expect(await screen.findByText('constructor')).toBeInTheDocument();
    expect(await screen.findByText('peer = peer-1')).toBeInTheDocument();
  });
});
