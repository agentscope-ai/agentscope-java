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
import { MemoryRouter, Outlet, Route, Routes, useLocation } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { I18nProvider, LOCALE_STORAGE_KEY } from '@/i18n';
import AppShell from './AppShell';

vi.mock('@/lib/auth', () => ({
  clearToken: vi.fn(),
  getUsername: () => 'alice',
  isAdmin: () => false,
}));

function LocationProbe() {
  const location = useLocation();
  return <span data-testid="location">{location.pathname}</span>;
}

function Content() {
  return (
    <>
      <LocationProbe />
      <Outlet />
    </>
  );
}

describe('AppShell locale switcher', () => {
  beforeEach(() => {
    window.localStorage.clear();
    window.localStorage.setItem(LOCALE_STORAGE_KEY, 'en');
  });

  it('switches navigation labels without changing the current route', () => {
    render(
      <I18nProvider>
        <MemoryRouter initialEntries={['/agents']}>
          <Routes>
            <Route element={<AppShell />}>
              <Route element={<Content />}>
                <Route path="/agents" element={<div>route content</div>} />
              </Route>
            </Route>
          </Routes>
        </MemoryRouter>
      </I18nProvider>,
    );

    expect(screen.getByText('Managed Agents')).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('/agents');

    fireEvent.click(screen.getByRole('button', { name: 'Switch to Chinese' }));

    expect(screen.getByText('托管智能体')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '切换到英文' })).toBeInTheDocument();
    expect(screen.getByTestId('location')).toHaveTextContent('/agents');
    expect(window.localStorage.getItem(LOCALE_STORAGE_KEY)).toBe('zh');
  });
});
