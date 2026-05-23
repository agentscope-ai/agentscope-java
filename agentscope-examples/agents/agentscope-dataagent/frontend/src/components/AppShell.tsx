import React, { useEffect, useRef, useState } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { clearToken, getToken } from '../api/auth';

function decodeJwt(token: string): Record<string, unknown> {
  try { return JSON.parse(atob(token.split('.')[1])); } catch { return {}; }
}

function getUsername(): string {
  const token = getToken();
  if (!token) return '';
  const p = decodeJwt(token);
  return (p.username as string) || (p.sub as string) || '';
}

function isAdminUser(): boolean {
  const token = getToken();
  if (!token) return false;
  const p = decodeJwt(token);
  const roles = Array.isArray(p.roles) ? (p.roles as string[]) : [];
  return roles.some(r => r.toLowerCase() === 'admin');
}

interface NavItem {
  label: string;
  path: string;
  icon: string;
}

const USER_NAV: NavItem[] = [
  { label: 'Chat',      path: '/chat',      icon: '💬' },
  { label: 'Workspace', path: '/workspace', icon: '🗂' },
  { label: 'Sessions',  path: '/sessions',  icon: '📋' },
  { label: 'Bindings',  path: '/bindings',  icon: '🔗' },
  { label: 'Skills',    path: '/skills',    icon: '🔧' },
  { label: 'Usage',     path: '/usage',     icon: '📈' },
];

const USER_MENU = [
  { label: 'Profile',    path: '/profile',    icon: '👤' },
  { label: 'Appearance', path: '/appearance', icon: '🎨' },
];

interface AppShellProps {
  children: React.ReactNode;
}

function navItemStyle(active: boolean): React.CSSProperties {
  return {
    display: 'flex',
    alignItems: 'center',
    gap: 7,
    width: '100%',
    background: active ? '#1e2235' : 'transparent',
    border: 'none',
    borderRadius: 7,
    padding: '6px 8px',
    cursor: 'pointer',
    fontSize: '0.82rem',
    color: active ? '#c4caff' : '#7c8bad',
    textAlign: 'left' as const,
    fontWeight: active ? 600 : 400,
  };
}

function NavButton({ item, location, navigate }: {
  item: NavItem;
  location: { pathname: string };
  navigate: (path: string) => void;
}) {
  const active = location.pathname === item.path ||
    (item.path !== '/' && location.pathname.startsWith(item.path + '/'));
  return (
    <button style={navItemStyle(active)} onClick={() => navigate(item.path)}>
      <span style={{ fontSize: '0.85rem', flexShrink: 0 }}>{item.icon}</span>
      <span>{item.label}</span>
    </button>
  );
}

function UserMenu({ username, onLogout }: {
  username: string;
  onLogout: () => void;
}) {
  const navigate  = useNavigate();
  const location  = useLocation();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    function handleClick(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    }
    if (open) document.addEventListener('mousedown', handleClick);
    return () => document.removeEventListener('mousedown', handleClick);
  }, [open]);

  useEffect(() => { setOpen(false); }, [location.pathname]);

  const isProfileActive = location.pathname === '/profile' || location.pathname === '/appearance';

  return (
    <div ref={ref} style={{ position: 'relative' as const }}>
      <button
        onClick={() => setOpen(o => !o)}
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          width: '100%',
          background: open || isProfileActive ? '#1e2235' : 'transparent',
          border: `1px solid ${open ? '#3d4168' : 'transparent'}`,
          borderRadius: 8,
          padding: '8px 10px',
          cursor: 'pointer',
          textAlign: 'left' as const,
        }}
      >
        <div style={{
          width: 28, height: 28, borderRadius: '50%', flexShrink: 0,
          background: '#1e2235',
          border: '1px solid #2d3148',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          fontSize: '0.75rem', fontWeight: 700, color: '#c4caff',
          userSelect: 'none' as const,
        }}>
          {username.charAt(0).toUpperCase() || '?'}
        </div>

        <div style={{ flex: 1, overflow: 'hidden' }}>
          <div style={{ fontSize: '0.8rem', fontWeight: 500, color: '#c4caff', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
            {username}
          </div>
        </div>
        <span style={{ fontSize: '0.65rem', color: '#3d4168', transform: open ? 'rotate(180deg)' : 'none', transition: 'transform 0.15s' }}>▲</span>
      </button>

      {open && (
        <div style={{
          position: 'absolute' as const,
          bottom: 'calc(100% + 6px)',
          left: 0,
          right: 0,
          background: '#1a1d27',
          border: '1px solid #2d3148',
          borderRadius: 10,
          boxShadow: '0 -8px 24px rgba(0,0,0,0.5)',
          overflow: 'hidden',
          zIndex: 100,
        }}>
          <div style={{ padding: '10px 12px 8px', borderBottom: '1px solid #1e2235' }}>
            <div style={{ fontSize: '0.8rem', fontWeight: 600, color: '#e2e8f0' }}>{username}</div>
          </div>

          {USER_MENU.map(item => {
            const active = location.pathname === item.path;
            return (
              <button
                key={item.path}
                onClick={() => { navigate(item.path); setOpen(false); }}
                style={{
                  display: 'flex', alignItems: 'center', gap: 8,
                  width: '100%', background: active ? '#1e2235' : 'transparent',
                  border: 'none', padding: '9px 12px', cursor: 'pointer',
                  fontSize: '0.82rem', color: active ? '#c4caff' : '#94a3b8',
                  textAlign: 'left' as const,
                }}
              >
                <span style={{ fontSize: '0.85rem' }}>{item.icon}</span>
                {item.label}
                {active && <span style={{ marginLeft: 'auto', width: 5, height: 5, borderRadius: '50%', background: '#6366f1', display: 'inline-block' }} />}
              </button>
            );
          })}

          {isAdminUser() && (
            <>
              <div style={{ height: 1, background: '#1e2235', margin: '4px 0' }} />
              <button
                onClick={() => { navigate('/admin/overview'); setOpen(false); }}
                style={{
                  display: 'flex', alignItems: 'center', gap: 8,
                  width: '100%', background: 'transparent', border: 'none',
                  padding: '9px 12px', cursor: 'pointer', fontSize: '0.82rem', color: '#94a3b8',
                  textAlign: 'left' as const,
                }}
              >
                <span style={{ fontSize: '0.85rem' }}>🛡</span>
                Admin console
              </button>
            </>
          )}

          <div style={{ height: 1, background: '#1e2235', margin: '4px 0' }} />
          <button
            onClick={() => { onLogout(); setOpen(false); }}
            style={{
              display: 'flex', alignItems: 'center', gap: 8,
              width: '100%', background: 'transparent', border: 'none',
              padding: '9px 12px', cursor: 'pointer', fontSize: '0.82rem', color: '#f87171',
              textAlign: 'left' as const,
            }}
          >
            <span style={{ fontSize: '0.85rem' }}>↩</span>
            Sign out
          </button>
        </div>
      )}
    </div>
  );
}

export default function AppShell({ children }: AppShellProps) {
  const navigate = useNavigate();
  const location = useLocation();
  const username = getUsername();

  const allNav = [...USER_NAV, ...USER_MENU];
  const currentNav = allNav.find(n =>
    location.pathname === n.path ||
    (n.path !== '/' && location.pathname.startsWith(n.path + '/'))
  );
  const pageTitle = currentNav ? `${currentNav.icon} ${currentNav.label}` : '';

  function logout() {
    clearToken();
    navigate('/login', { replace: true });
  }

  return (
    <div style={{ display: 'flex', height: '100vh', background: '#0f1117', color: '#e2e8f0', overflow: 'hidden' }}>

      <div style={{
        width: 200, background: '#0d0f18', borderRight: '1px solid #1a1d2e',
        display: 'flex', flexDirection: 'column', flexShrink: 0, overflowY: 'auto',
      }}>
        <div style={{ padding: '16px 14px 12px', borderBottom: '1px solid #1a1d2e', flexShrink: 0 }}>
          <span style={{ fontWeight: 700, color: '#6366f1', fontSize: '0.95rem', letterSpacing: '-0.02em', display: 'block' }}>
            📊 DataAgent
          </span>
          <span style={{ fontSize: '0.68rem', color: '#4b5280', marginTop: 2, display: 'block' }}>
            Tenant data-analysis assistant
          </span>
        </div>

        <div style={{ padding: '10px 8px 4px' }}>
          <span style={{ fontSize: '0.65rem', fontWeight: 700, letterSpacing: '0.08em', color: '#3d4168', textTransform: 'uppercase' as const, padding: '0 6px', marginBottom: 4, display: 'block' }}>
            Workspace
          </span>
          {USER_NAV.map(item => (
            <NavButton key={item.path} item={item} location={location} navigate={navigate} />
          ))}
        </div>

        <div style={{ flex: 1 }} />

        <div style={{ padding: '8px', borderTop: '1px solid #1a1d2e', flexShrink: 0 }}>
          <UserMenu username={username} onLogout={logout} />
        </div>
      </div>

      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
        <div style={{
          height: 44, background: '#0d0f18', borderBottom: '1px solid #1a1d2e',
          display: 'flex', alignItems: 'center', padding: '0 16px', flexShrink: 0,
        }}>
          <span style={{ fontSize: '0.85rem', color: '#6b7280', flex: 1 }}>{pageTitle}</span>
        </div>

        <div style={{ flex: 1, overflow: 'auto' }}>{children}</div>
      </div>
    </div>
  );
}
