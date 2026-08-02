import { NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import {
  Boxes,
  Bot,
  Database,
  FolderOpen,
  HardDrive,
  KeyRound,
  LayoutDashboard,
  LogOut,
  MessageSquare,
  Radio,
  Rocket,
  Settings,
  Users,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { clearToken, getUsername, isAdmin } from '@/lib/auth';
import { Button } from '@/components/ui/button';

const buildNav = [
  { to: '/agents', label: 'Agents', icon: Bot },
  { to: '/workspaces', label: 'Workspaces', icon: FolderOpen },
  { to: '/environments', label: 'Environments', icon: HardDrive },
  { to: '/memory-stores', label: 'Memory', icon: Database },
  { to: '/vaults', label: 'Vaults', icon: KeyRound },
  { to: '/deployments', label: 'Deployments', icon: Rocket },
  { to: '/channels', label: 'Channels', icon: Radio, admin: true },
];

const operateNav = [
  { to: '/operate', label: 'Overview', icon: LayoutDashboard, end: true },
  { to: '/operate/agents', label: 'Agents', icon: Boxes },
  { to: '/operate/sessions', label: 'Sessions', icon: MessageSquare },
  { to: '/operate/governance', label: 'Governance', icon: Settings },
];

function SideLink({
  to,
  label,
  icon: Icon,
  end,
}: {
  to: string;
  label: string;
  icon: React.ComponentType<{ className?: string }>;
  end?: boolean;
}) {
  return (
    <NavLink
      to={to}
      end={end}
      className={({ isActive }) =>
        cn(
          'relative flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition-colors',
          isActive
            ? 'bg-accent text-accent-foreground before:absolute before:inset-y-1.5 before:left-0 before:w-[3px] before:rounded-full before:bg-primary'
            : 'text-slate-600 hover:bg-muted hover:text-foreground',
        )
      }
    >
      <Icon className="h-5 w-5 shrink-0" />
      {label}
    </NavLink>
  );
}

export default function AppShell() {
  const location = useLocation();
  const navigate = useNavigate();
  const username = getUsername();
  const admin = isAdmin();
  const zone = location.pathname.startsWith('/operate') ? 'operate' : 'build';

  return (
    <div className="flex h-full min-h-0 bg-canvas">
      <aside className="flex w-64 shrink-0 flex-col border-r border-border bg-white">
        <div className="border-b border-border px-5 py-5">
          <button
            className="text-left"
            onClick={() => navigate(zone === 'operate' ? '/operate' : '/agents')}
          >
            <div className="text-lg font-bold tracking-tight text-foreground">aistio</div>
            <div className="mt-0.5 text-sm text-muted-foreground">Control plane console</div>
          </button>
          <div className="mt-4 grid grid-cols-2 gap-1 rounded-lg bg-slate-100 p-1">
            <button
              className={cn(
                'rounded-md px-2 py-2 text-sm font-semibold transition-colors',
                zone === 'build' ? 'bg-white text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground',
              )}
              onClick={() => navigate('/agents')}
            >
              Build
            </button>
            <button
              className={cn(
                'rounded-md px-2 py-2 text-sm font-semibold transition-colors',
                zone === 'operate' ? 'bg-white text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground',
              )}
              onClick={() => navigate('/operate')}
            >
              Operate
            </button>
          </div>
        </div>

        <nav className="flex-1 space-y-1 overflow-y-auto p-3">
          {(zone === 'build' ? buildNav : operateNav)
            .filter((item) => !('admin' in item && item.admin) || admin)
            .map((item) => (
              <SideLink key={item.to} to={item.to} label={item.label} icon={item.icon} end={'end' in item ? item.end : false} />
            ))}
          {zone === 'build' && admin && (
            <SideLink to="/admin/users" label="Users" icon={Users} />
          )}
        </nav>

        <div className="border-t border-border p-4">
          <div className="mb-2.5 truncate px-2 text-sm text-muted-foreground">{username || 'guest'}</div>
          <div className="flex gap-1">
            <Button variant="ghost" size="sm" className="flex-1 justify-start" onClick={() => navigate('/profile')}>
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
