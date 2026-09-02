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
import { I18nProvider } from '@/i18n';
import ShareAgentDialog from './ShareAgentDialog';

const shareApi = vi.hoisted(() => ({
  addShare: vi.fn(),
  listShares: vi.fn(),
  revokeShare: vi.fn(),
}));

const adminApi = vi.hoisted(() => ({
  listUsers: vi.fn(),
}));

const authApi = vi.hoisted(() => ({
  isAdmin: vi.fn(),
}));

vi.mock('../api/shares', () => shareApi);
vi.mock('../api/admin', () => adminApi);
vi.mock('../api/auth', () => authApi);

describe('ShareAgentDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    window.localStorage.clear();
    authApi.isAdmin.mockReturnValue(false);
    shareApi.listShares.mockResolvedValue([
      {
        granteeType: 'USER',
        granteeId: 'user-1',
        tier: 'constructor',
        createdAt: 1,
        createdBy: 'owner-1',
      },
    ]);
  });

  it('preserves a prototype-named share tier returned by the server', async () => {
    render(
      <I18nProvider>
        <ShareAgentDialog
          agent={{
            id: 'agent-1',
            name: 'Agent One',
            scope: 'user',
            ownerId: 'owner-1',
            createdAt: 1,
            updatedAt: 1,
          }}
          onClose={vi.fn()}
        />
      </I18nProvider>,
    );

    expect(await screen.findByText('constructor')).toBeInTheDocument();
  });
});
