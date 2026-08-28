/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

import { useCallback, useEffect, useState, type ComponentType } from 'react';
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import {
  Activity,
  Bot,
  Boxes,
  BriefcaseBusiness,
  CircleGauge,
  ClipboardCheck,
  Cpu,
  Database,
  FileStack,
  LogOut,
  Menu,
  MessageSquare,
  Network,
  PlayCircle,
  Search,
  Server,
  Settings2,
  ShieldCheck,
  UsersRound,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { clearToken, getRoles, getUsername, isAdmin } from '@/lib/auth';
import { Button } from '@/components/ui/button';
import { useControlPlaneScope } from './ScopeContext';
import { CommandPalette, useCommandPaletteShortcut } from './CommandPalette';
import { useCollaborationEvents } from './useCollaborationEvents';

type NavItem = {
  to: string;
  label: string;
  icon: ComponentType<{ className?: string }>;
  end?: boolean;
  admin?: boolean;
};

type NavGroup = { label?: string; items: NavItem[] };

const workNavigation: NavGroup[] = [
  {
    items: [{ to: '/work/overview', label: 'Overview', icon: CircleGauge, end: true }],
  },
  {
    label: 'Work',
    items: [
      { to: '/work/issues', label: 'Issues', icon: FileStack },
      { to: '/work/approvals', label: 'Inbox & approvals', icon: ClipboardCheck },
      { to: '/work/automations', label: 'Schedules / automations', icon: BriefcaseBusiness },
      { to: '/work/activity', label: 'Activity', icon: Activity },
    ],
  },
];

const agentCenterNavigation: NavGroup[] = [
  {
    label: 'Catalog & design',
    items: [
      { to: '/agent-center/agents', label: 'Agents', icon: Bot },
      { to: '/agent-center/teams', label: 'Teams', icon: UsersRound },
      { to: '/agent-center/workflows', label: 'Workflows', icon: Network },
      { to: '/agent-center/endpoints', label: 'Applications / endpoints', icon: PlayCircle },
      { to: '/agent-center/entrypoints', label: 'Entrypoints / channels', icon: Network },
    ],
  },
  {
    label: 'Resources',
    items: [
      { to: '/agent-center/workspaces', label: 'Workspaces', icon: FileStack },
      { to: '/agent-center/environments', label: 'Skills & tools', icon: Settings2 },
      { to: '/agent-center/memory', label: 'Memory', icon: Database },
      { to: '/agent-center/vaults', label: 'Vault', icon: ShieldCheck },
    ],
  },
];

const operationsNavigation: NavGroup[] = [
  {
    items: [{ to: '/operations/overview', label: 'Overview', icon: CircleGauge, end: true }],
  },
  {
    label: 'Fleet',
    items: [
      { to: '/operations/instances', label: 'Agent instances / fleet', icon: Boxes },
      { to: '/operations/runtime/hosts', label: 'Runtime hosts', icon: Server },
      { to: '/operations/runtime/profiles', label: 'Profiles / pools', icon: Cpu },
    ],
  },
  {
    label: 'Execution',
    items: [
      { to: '/operations/sessions', label: 'Sessions', icon: MessageSquare },
      { to: '/operations/runs', label: 'Runs', icon: PlayCircle },
      { to: '/operations/tasks', label: 'AgentTasks / attempts', icon: Activity },
    ],
  },
  {
    label: 'Governance',
    items: [
      { to: '/operations/runtime/policy', label: 'Usage / budget / policy', icon: ShieldCheck },
      { to: '/operations/governance', label: 'Audit / dead letters', icon: ClipboardCheck },
    ],
  },
];

const routeLabels: Array<[string, string, string]> = [
  ['/work/overview', 'Work Hub', 'Overview'],
  ['/work/issues', 'Work Hub', 'Issues'],
  ['/work/approvals', 'Work Hub', 'Inbox & approvals'],
  ['/work/automations', 'Work Hub', 'Schedules / automations'],
  ['/work/activity', 'Work Hub', 'Activity'],
  ['/agent-center/agents', 'Agent Center', 'Agents'],
  ['/agent-center/teams', 'Agent Center', 'Teams'],
  ['/agent-center/workflows', 'Agent Center', 'Workflows'],
  ['/agent-center/endpoints', 'Agent Center', 'Applications / endpoints'],
  ['/operations/overview', 'Operations', 'Overview'],
  ['/operations/instances', 'Operations', 'Agent instances'],
  ['/operations/sessions', 'Operations', 'Sessions'],
  ['/operations/runs', 'Operations', 'Runs'],
  ['/operations/tasks', 'Operations', 'AgentTasks / attempts'],
  ['/operations/runtime', 'Operations', 'Runtime fleet'],
  ['/operations/governance', 'Operations', 'Audit / dead letters'],
];

function matches(pathname: string, to: string, end?: boolean): boolean {
  if (end) return pathname === to;
  return pathname === to || pathname.startsWith(`${to}/`);
}

function SidebarLink({ item }: { item: NavItem }) {
  const { scopedPath } = useControlPlaneScope();
  const location = useLocation();
  const Icon = item.icon;
  const active = matches(location.pathname, item.to, item.end);
  return (
    <NavLink
	  to={scopedPath(item.to)}
      className={cn(
        'group flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors',
        active
          ? 'bg-accent text-accent-foreground'
          : 'text-slate-600 hover:bg-muted hover:text-foreground',
      )}
    >
      <Icon className="h-[18px] w-[18px] shrink-0 text-slate-500 group-hover:text-current" />
      <span className="truncate">{item.label}</span>
    </NavLink>
  );
}

function ScopeSelector({ title }: { title?: string }) {
  const { tenant, namespace, setScope } = useControlPlaneScope();
  const [draftTenant, setDraftTenant] = useState(tenant);
  const [draftNamespace, setDraftNamespace] = useState(namespace);

  useEffect(() => setDraftTenant(tenant), [tenant]);
  useEffect(() => setDraftNamespace(namespace), [namespace]);

  return (
    <div className="grid grid-cols-2 gap-2 border-b border-border px-3 py-3">
      {title && <div className="col-span-2 text-[11px] font-semibold uppercase tracking-[0.12em] text-slate-400">{title}</div>}
      <label className="grid gap-1 text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
        Tenant
        <input
          aria-label="Tenant"
          className="h-8 min-w-0 rounded-md border border-border bg-muted px-2 text-xs normal-case tracking-normal text-foreground"
          value={draftTenant}
          onChange={(event) => setDraftTenant(event.target.value)}
          onBlur={() => setScope(draftTenant, draftNamespace)}
          onKeyDown={(event) => { if (event.key === 'Enter') setScope(draftTenant, draftNamespace); }}
        />
      </label>
      <label className="grid gap-1 text-[11px] font-medium uppercase tracking-wide text-muted-foreground">
        Namespace
        <input
          aria-label="Namespace"
          className="h-8 min-w-0 rounded-md border border-border bg-muted px-2 text-xs normal-case tracking-normal text-foreground"
          value={draftNamespace}
          onChange={(event) => setDraftNamespace(event.target.value)}
          onBlur={() => setScope(draftTenant, draftNamespace)}
          onKeyDown={(event) => { if (event.key === 'Enter') setScope(draftTenant, draftNamespace); }}
        />
      </label>
    </div>
  );
}

export default function AppShell() {
  const location = useLocation();
  const navigate = useNavigate();
  const username = getUsername();
  const admin = isAdmin();
  const roles = getRoles().map((role) => role.toLowerCase());
  const scope = useControlPlaneScope();
	useCollaborationEvents(scope.tenant, scope.namespace);
  const area = location.pathname.startsWith('/agent-center')
    ? 'agent-center'
    : location.pathname.startsWith('/operations') ? 'operations' : 'work';
  const canAgentCenter = admin || roles.includes('agent_developer') || roles.includes('operator');
  const canOperations = admin || roles.includes('operator');
  const navigation = area === 'agent-center'
    ? agentCenterNavigation
    : area === 'operations' ? operationsNavigation : workNavigation;
  const home = area === 'agent-center' ? '/agent-center/agents' : area === 'operations' ? '/operations/overview' : '/work/overview';
  const [commandOpen, setCommandOpen] = useState(false);
  const [mobileNavOpen, setMobileNavOpen] = useState(false);
  const openCommand = useCallback(() => {
    if (area === 'work') setCommandOpen(true);
  }, [area]);
  useCommandPaletteShortcut(openCommand);
  useEffect(() => setMobileNavOpen(false), [location.pathname]);
  const context = routeLabels.find(([prefix]) => matches(location.pathname, prefix));

  return (
    <div className="flex h-full min-h-0 bg-canvas">
      <a
        href="#main-content"
        className="fixed left-3 top-3 z-50 -translate-y-20 rounded-md bg-primary px-3 py-2 text-sm text-white focus:translate-y-0"
      >
        Skip to content
      </a>
      {mobileNavOpen && <button type="button" aria-label="Close navigation" className="fixed inset-0 z-30 bg-slate-950/35 lg:hidden" onClick={() => setMobileNavOpen(false)} />}
      <aside className={cn('fixed inset-y-0 left-0 z-40 flex w-64 shrink-0 flex-col border-r border-border bg-white transition-transform lg:static lg:z-auto lg:translate-x-0', mobileNavOpen ? 'translate-x-0' : '-translate-x-full')}>
        <div className="border-b border-border px-4 py-4">
          <Link className="flex items-center gap-3 rounded-lg" to={home}>
            <img src="/logo.svg" alt="AgentScope" className="h-9 w-9 shrink-0" width={36} height={36} />
            <div className="min-w-0">
              <div className="text-lg font-bold tracking-tight text-foreground">AgentScope Service</div>
              <div className="truncate text-xs text-muted-foreground">{area === 'work' ? 'Work Hub' : area === 'agent-center' ? 'Agent Center' : 'Operations'}</div>
            </div>
          </Link>
        </div>

        <ScopeSelector />

        <nav aria-label="Primary navigation" className="flex-1 space-y-5 overflow-y-auto px-3 py-4">
          {navigation.map((group, index) => (
            <div key={group.label || `primary-${index}`} className="space-y-1">
              {group.label && (
                <div className="px-3 pb-1 text-[11px] font-semibold uppercase tracking-[0.12em] text-slate-400">
                  {group.label}
                </div>
              )}
              {group.items.filter((item) => !item.admin || admin).map((item) => (
                <SidebarLink key={item.to} item={item} />
              ))}
            </div>
          ))}
        </nav>

        <div className="border-t border-border p-3">
          <div className="mb-2 truncate px-2 text-xs text-muted-foreground">Signed in as {username || 'guest'}</div>
          <div className="flex gap-1">
            {admin && <Button variant="ghost" size="sm" className="flex-1" onClick={() => navigate('/managed/admin/users')}>Users</Button>}
            <Button variant="ghost" size="sm" className="flex-1" onClick={() => navigate('/managed/profile')}>Profile</Button>
            <Button
              variant="ghost"
              size="icon"
              aria-label="Sign out"
              title="Sign out"
              onClick={() => { clearToken(); navigate('/login'); }}
            >
              <LogOut className="h-4 w-4" />
            </Button>
          </div>
        </div>
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex min-h-14 shrink-0 flex-wrap items-center justify-between gap-3 border-b border-border bg-white px-3 py-2 sm:px-6">
          <div className="flex min-w-0 items-center gap-3">
            <Button variant="ghost" size="icon" className="lg:hidden" aria-label="Open navigation" onClick={() => setMobileNavOpen(true)}><Menu className="h-4 w-4" /></Button>
            <nav aria-label="Product area" className="flex rounded-lg border border-border bg-muted p-1 text-xs font-medium">
              <Link
                to="/work/overview"
                aria-current={area === 'work' ? 'page' : undefined}
                className={cn('rounded-md px-3 py-1.5 transition-colors', area === 'work' ? 'bg-white text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground')}
              >
                Work Hub
              </Link>
              {canAgentCenter && <Link
                to="/agent-center/agents"
                aria-current={area === 'agent-center' ? 'page' : undefined}
                className={cn('rounded-md px-3 py-1.5 transition-colors', area === 'agent-center' ? 'bg-white text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground')}
              >Agent Center</Link>}
              {canOperations && <Link
                to="/operations/overview"
                aria-current={area === 'operations' ? 'page' : undefined}
                className={cn('rounded-md px-3 py-1.5 transition-colors', area === 'operations' ? 'bg-white text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground')}
              >Operations</Link>}
            </nav>
            <div className="hidden min-w-0 items-center gap-2 text-sm xl:flex">
              <span className="text-muted-foreground">{context?.[1] || 'Console'}</span>
              <span className="text-slate-300">/</span>
              <span className="truncate font-medium text-foreground">{context?.[2] || 'Resource'}</span>
            </div>
          </div>
          <div className="flex items-center gap-3">
            {area === 'work' && <button type="button" onClick={openCommand} className="flex h-8 items-center gap-2 rounded-lg border border-border bg-muted px-2 text-xs text-muted-foreground hover:bg-slate-100 sm:min-w-52 sm:px-3" aria-label="Search work"><Search className="h-3.5 w-3.5" /><span className="hidden flex-1 text-left sm:block">Search work</span><kbd className="hidden rounded border bg-white px-1.5 py-0.5 font-mono text-[10px] sm:block">⌘K</kbd></button>}
            <div className="hidden font-mono text-xs text-muted-foreground md:block">{scope.tenant} / {scope.namespace}</div>
          </div>
        </header>
        <main id="main-content" tabIndex={-1} className="min-w-0 flex-1 overflow-auto bg-canvas focus:outline-none">
          <Outlet />
        </main>
      </div>
      {area === 'work' && <CommandPalette open={commandOpen} onOpenChange={setCommandOpen} />}
    </div>
  );
}
