/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

import { useCallback, useEffect, useState, type ComponentType } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import {
  Bot,
  BriefcaseBusiness,
  CircleGauge,
  ClipboardCheck,
  Database,
  FileStack,
  LogOut,
  Menu,
  MessageSquare,
  Network,
  Search,
  Settings2,
  ShieldCheck,
  UsersRound,
} from 'lucide-react';
import { namespaceCan } from '@/lib/namespaceScope';
import { cn } from '@/lib/utils';
import { clearToken, getRoles, getUsername, isAdmin } from '@/lib/auth';
import { Button } from '@/components/ui/button';
import { useControlPlaneScope } from './ScopeContext';
import { CommandPalette, useCommandPaletteShortcut } from './CommandPalette';
import { useCollaborationEvents } from './useCollaborationEvents';
import { getInboxSummary } from '@/api/collaboration';
import { formatAttentionCount, type ApprovalAttentionSummary } from './approvalAttention';

type NavItem = {
  to: string;
  label: string;
  icon: ComponentType<{ className?: string }>;
  end?: boolean;
  admin?: boolean;
  operator?: boolean;
  agentCenter?: boolean;
  configure?: boolean;
};

type NavGroup = { label?: string; items: NavItem[] };

const navigation: NavGroup[] = [
  { items: [{ to: '/work/overview', label: 'Overview', icon: CircleGauge, end: true }] },
  {
    label: 'Work',
    items: [
      { to: '/work/chat', label: 'Chat', icon: MessageSquare },
      { to: '/work/issues', label: 'Issues', icon: FileStack },
      { to: '/work/inbox', label: 'Inbox', icon: ClipboardCheck },
      { to: '/work/automations', label: 'Automations', icon: BriefcaseBusiness },
    ],
  },
  {
    label: 'Design',
    items: [
      { to: '/agent-center/agents', label: 'Agents', icon: Bot, agentCenter: true },
      { to: '/agent-center/teams', label: 'Teams', icon: UsersRound, agentCenter: true },
      { to: '/agent-center/workflows', label: 'Workflows', icon: Network, agentCenter: true },
      { to: '/agent-center/entrypoints', label: 'Channels', icon: Network, agentCenter: true, configure: true },
    ],
  },
  {
    label: 'Resources',
    items: [
      { to: '/agent-center/workspaces', configure: true, label: 'Workspaces', icon: FileStack, agentCenter: true },
      { to: '/agent-center/environments', configure: true, label: 'Environments', icon: Settings2, agentCenter: true },
      { to: '/agent-center/memory', configure: true, label: 'Memory', icon: Database, agentCenter: true },
      { to: '/agent-center/vaults', configure: true, label: 'Vault', icon: ShieldCheck, agentCenter: true },
      { to: '/work/permissions', label: 'Permissions', icon: ShieldCheck },
    ],
  },
];

const routeLabels: Array<[string, string]> = [
  ['/work/overview', 'Overview'],
  ['/work/chat', 'Chat'],
  ['/work/issues', 'Issues'],
  ['/work/inbox', 'Inbox'],
  ['/work/automations', 'Automations'],
  ['/work/activity', 'Activity'],
  ['/work/executions', 'Executions'],
  ['/work/sessions', 'Sessions'],
  ['/agent-center/agents', 'Agents'],
  ['/agent-center/teams', 'Teams'],
  ['/agent-center/workflows', 'Workflows'],
  ['/agent-center/endpoints', 'API details'],
  ['/agent-center/entrypoints', 'Channels'],
  ['/agent-center/workspaces', 'Workspaces'],
  ['/agent-center/environments', 'Environments'],
  ['/agent-center/memory', 'Memory'],
  ['/agent-center/vaults', 'Vault'],
  ['/managed/profile', 'Profile'],
  ['/managed/admin/users', 'Users'],
];

function matches(pathname: string, to: string, end?: boolean): boolean {
  if (end) return pathname === to;
  return pathname === to || pathname.startsWith(`${to}/`);
}

function SidebarLink({ item, attention }: { item: NavItem; attention?: ApprovalAttentionSummary }) {
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
      <span className="min-w-0 flex-1 truncate">{item.label}</span>
      {!!attention?.total && (
        <span
          className="inline-flex min-w-5 shrink-0 items-center justify-center rounded-full bg-amber-100 px-1.5 py-0.5 text-[11px] font-semibold leading-none text-amber-800"
          aria-label={`${attention.total} items need attention`}
          title={`${attention.pending} pending confirmation${attention.pending === 1 ? '' : 's'} · ${attention.unread} unread notification${attention.unread === 1 ? '' : 's'}`}
        >
          {formatAttentionCount(attention.total)}
        </span>
      )}
    </NavLink>
  );
}

function ScopeSelector({ title }: { title?: string }) {
  const { tenant, namespace, namespaces, selectorVisible, setScope } = useControlPlaneScope();
  if (!selectorVisible) return null;
  return <div className="border-b border-border px-3 py-3">
    {title && <div className="mb-2 text-xs text-muted-foreground">{title}</div>}
    <label className="grid gap-1 text-xs text-muted-foreground">Namespace
      <select aria-label="Namespace" className="h-9 w-full rounded-md border border-border bg-background px-2 text-sm text-foreground" value={`${tenant}/${namespace}`} onChange={e => {
        const next = namespaces.find(n => `${n.tenant}/${n.name}` === e.target.value);
        if (next) setScope(next.tenant, next.name);
      }}>{namespaces.map(n => <option key={`${n.tenant}/${n.name}`} value={`${n.tenant}/${n.name}`}>{n.displayName}{n.kind === 'personal' ? ' (personal)' : ''}</option>)}</select>
    </label>
  </div>;
}

export default function AppShell() {
  const location = useLocation();
  const navigate = useNavigate();
  const username = getUsername();
  const admin = isAdmin();
  const roles = getRoles().map((role) => role.toLowerCase());
  const scope = useControlPlaneScope();
	useCollaborationEvents(scope.tenant, scope.namespace);
  const inboxSummary = useQuery({
    queryKey: ['inbox-summary', scope.tenant, scope.namespace],
    queryFn: () => getInboxSummary(scope.tenant, scope.namespace),
    refetchInterval: 5_000,
  });
  const summary = inboxSummary.data?.summary;
  const approvalAttention = summary ? { total: summary.attentionTotal, unread: summary.unread, pending: summary.pendingApprovals } : undefined;
  const canAgentCenter = scope.roles.length > 0 || admin || roles.includes('agent_developer') || roles.includes('operator');
  const visibleNavigation = navigation.map((group) => ({
    ...group,
    items: group.items.filter((item) =>
      (!item.configure || namespaceCan(scope.roles, 'configure')) && (!item.admin || admin) && (!item.operator || admin || roles.includes('operator')) && (!item.agentCenter || canAgentCenter)),
  })).filter((group) => group.items.length > 0);
  const [commandOpen, setCommandOpen] = useState(false);
  const [mobileNavOpen, setMobileNavOpen] = useState(false);
  const openCommand = useCallback(() => setCommandOpen(true), []);
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
          <Link className="flex items-center gap-3 rounded-lg" to="/work/overview">
            <img src="/logo.svg" alt="AgentScope" className="h-9 w-9 shrink-0" width={36} height={36} />
            <div className="min-w-0">
              <div className="text-lg font-bold tracking-tight text-foreground">AgentScope Service</div>
              <div className="truncate text-xs text-muted-foreground">Control plane</div>
            </div>
          </Link>
        </div>

        <ScopeSelector />

        <nav aria-label="Primary navigation" className="flex-1 space-y-5 overflow-y-auto px-3 py-4">
          {visibleNavigation.map((group, index) => <div key={group.label || `primary-${index}`} className="space-y-1">
            {group.label && <div className="px-3 pb-1 text-[11px] font-semibold uppercase tracking-[0.12em] text-slate-400">{group.label}</div>}
            {group.items.map((item) => <SidebarLink key={item.to} item={item} attention={item.to === '/work/inbox' ? approvalAttention : undefined} />)}
          </div>)}
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
            <div className="hidden min-w-0 text-sm font-medium text-foreground xl:block">{context?.[1] || 'AgentScope'}</div>
          </div>
          <div className="flex items-center gap-3">
            <button type="button" onClick={openCommand} className="flex h-8 items-center gap-2 rounded-lg border border-border bg-muted px-2 text-xs text-muted-foreground hover:bg-slate-100 sm:min-w-52 sm:px-3" aria-label="Search"><Search className="h-3.5 w-3.5" /><span className="hidden flex-1 text-left sm:block">Search</span><kbd className="hidden rounded border bg-white px-1.5 py-0.5 font-mono text-[10px] sm:block">⌘K</kbd></button>
            {scope.selectorVisible && <div className="hidden font-mono text-xs text-muted-foreground md:block">{scope.tenant} / {scope.namespace}</div>}
          </div>
        </header>
        <main id="main-content" tabIndex={-1} className="min-w-0 flex-1 overflow-auto bg-white focus:outline-none">
          <Outlet />
        </main>
      </div>
      <CommandPalette open={commandOpen} onOpenChange={setCommandOpen} />
    </div>
  );
}
