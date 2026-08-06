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

import { useEffect, useState } from 'react';
import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import {
  Bot,
  ChevronRight,
  LayoutDashboard,
  LogOut,
  UsersRound,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { clearToken, getUsername, isAdmin } from '@/lib/auth';
import { Button } from '@/components/ui/button';

type ZoneId = 'dashboard' | 'managed' | 'teams';

type NavItem = {
  to: string;
  label: string;
  end?: boolean;
  admin?: boolean;
};

type NavSection = {
  id: ZoneId;
  label: string;
  icon: React.ComponentType<{ className?: string }>;
  match: (pathname: string) => boolean;
  home: string;
  items: NavItem[];
};

const managedPrefixes = [
  '/agents',
  '/sessions',
  '/workspaces',
  '/environments',
  '/memory-stores',
  '/vaults',
  '/deployments',
  '/channels',
];

const navSections: NavSection[] = [
  {
    id: 'dashboard',
    label: 'Dashboard',
    icon: LayoutDashboard,
    match: (pathname) => pathname.startsWith('/operate'),
    home: '/operate',
    items: [
      { to: '/operate', label: 'Overview', end: true },
      { to: '/operate/agents', label: 'Agents' },
      { to: '/operate/sessions', label: 'Sessions' },
      { to: '/operate/governance', label: 'Governance' },
    ],
  },
  {
    id: 'managed',
    label: 'Managed Agents',
    icon: Bot,
    match: (pathname) =>
      managedPrefixes.some(
        (prefix) => pathname === prefix || pathname.startsWith(`${prefix}/`),
      ),
    home: '/agents',
    items: [
      { to: '/agents', label: 'Agents' },
      { to: '/sessions', label: 'Sessions' },
      { to: '/workspaces', label: 'Workspaces' },
      { to: '/environments', label: 'Environments' },
      { to: '/memory-stores', label: 'Memory' },
      { to: '/vaults', label: 'Vaults' },
      { to: '/deployments', label: 'Deployments' },
      { to: '/channels', label: 'Channels', admin: true },
    ],
  },
  {
    id: 'teams',
    label: 'Teams',
    icon: UsersRound,
    match: (pathname) => pathname.startsWith('/teams'),
    home: '/teams',
    items: [
      { to: '/teams', label: 'Overview', end: true },
      { to: '/teams/list', label: 'Teams' },
      { to: '/teams/templates', label: 'Templates' },
    ],
  },
];

function resolveZone(pathname: string): ZoneId | null {
  for (const section of navSections) {
    if (section.match(pathname)) return section.id;
  }
  return null;
}

function SideLink({
  to,
  label,
  end,
}: {
  to: string;
  label: string;
  end?: boolean;
}) {
  return (
    <NavLink
      to={to}
      end={end}
      className={({ isActive }) =>
        cn(
          'relative flex items-center rounded-lg px-3 py-2 text-sm font-medium transition-colors',
          isActive
            ? 'bg-accent text-accent-foreground before:absolute before:inset-y-1.5 before:left-0 before:w-[3px] before:rounded-full before:bg-primary'
            : 'text-slate-600 hover:bg-muted hover:text-foreground',
        )
      }
    >
      {label}
    </NavLink>
  );
}

function NavGroup({
  section,
  open,
  onToggle,
  admin,
}: {
  section: NavSection;
  open: boolean;
  onToggle: () => void;
  admin: boolean;
}) {
  const Icon = section.icon;
  const items = section.items.filter((item) => !item.admin || admin);

  return (
    <div className="space-y-0.5">
      <button
        type="button"
        onClick={onToggle}
        aria-expanded={open}
        className={cn(
          'flex w-full items-center gap-2 rounded-lg px-3 py-2.5 text-sm font-semibold transition-colors',
          open
            ? 'bg-slate-100 text-foreground'
            : 'text-slate-700 hover:bg-muted hover:text-foreground',
        )}
      >
        <ChevronRight
          className={cn(
            'h-4 w-4 shrink-0 text-muted-foreground transition-transform duration-200',
            open && 'rotate-90',
          )}
        />
        <Icon className="h-5 w-5 shrink-0" />
        <span className="truncate text-left">{section.label}</span>
      </button>

      {open && (
        <div className="ml-3 space-y-0.5 border-l border-border pl-2">
          {items.map((item) => (
            <SideLink
              key={item.to}
              to={item.to}
              label={item.label}
              end={item.end}
            />
          ))}
        </div>
      )}
    </div>
  );
}

export default function AppShell() {
  const location = useLocation();
  const navigate = useNavigate();
  const username = getUsername();
  const admin = isAdmin();
  const activeZone = resolveZone(location.pathname);
  const activeHome =
    navSections.find((s) => s.id === activeZone)?.home ?? '/agents';

  // Default all collapsed; auto-expand the section that owns the current route.
  const [openSections, setOpenSections] = useState<Record<ZoneId, boolean>>({
    dashboard: false,
    managed: false,
    teams: false,
  });

  useEffect(() => {
    if (!activeZone) return;
    setOpenSections((prev) => {
      if (prev[activeZone]) return prev;
      return { ...prev, [activeZone]: true };
    });
  }, [activeZone]);

  const toggleSection = (id: ZoneId) => {
    setOpenSections((prev) => ({ ...prev, [id]: !prev[id] }));
  };

  return (
    <div className="flex h-full min-h-0 bg-canvas">
      <aside className="flex w-64 shrink-0 flex-col border-r border-border bg-white">
        <div className="border-b border-border px-5 py-5">
          <button className="flex items-center gap-3 text-left" onClick={() => navigate(activeHome)}>
            <img
              src="/logo.svg"
              alt="AgentScope"
              className="h-9 w-9 shrink-0"
              width={36}
              height={36}
            />
            <div>
              <div className="text-lg font-bold tracking-tight text-foreground">aistio</div>
              <div className="mt-0.5 text-sm text-muted-foreground">Control plane console</div>
            </div>
          </button>
        </div>

        <nav className="flex-1 space-y-2 overflow-y-auto p-3">
          {navSections.map((section) => (
            <NavGroup
              key={section.id}
              section={section}
              open={openSections[section.id]}
              onToggle={() => toggleSection(section.id)}
              admin={admin}
            />
          ))}
        </nav>

        <div className="border-t border-border p-4">
          <div className="mb-2.5 truncate px-2 text-sm text-muted-foreground">
            {username || 'guest'}
          </div>
          <div className="flex gap-1">
            {admin && (
              <Button
                variant="ghost"
                size="sm"
                className="flex-1 justify-start"
                onClick={() => navigate('/admin/users')}
              >
                Users
              </Button>
            )}
            <Button
              variant="ghost"
              size="sm"
              className="flex-1 justify-start"
              onClick={() => navigate('/profile')}
            >
              Profile
            </Button>
            <Button
              variant="ghost"
              size="icon"
              title="Sign out"
              onClick={() => {
                clearToken();
                navigate('/login');
              }}
            >
              <LogOut className="h-4 w-4" />
            </Button>
          </div>
        </div>
      </aside>

      <main className="min-w-0 flex-1 overflow-auto bg-canvas">
        <Outlet />
      </main>
    </div>
  );
}
