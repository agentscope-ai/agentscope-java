import React, { useEffect, useRef, useState } from 'react';
import { useNavigate, useLocation } from 'react-router-dom';
import { clearToken, getToken } from '../../api/auth';

function decodeJwt(token: string): Record<string, unknown> {
  try { return JSON.parse(atob(token.split('.')[1])); } catch { return {}; }
}

function getUsername(): string {
  const token = getToken();
  if (!token) return '';
  const p = decodeJwt(token);
  return (p.username as string) || (p.sub as string) || '';
}

interface NavItem {
  label: string;
  path: string;
  icon: string;
}

const ADMIN_NAV: NavItem[] = [
  { label: 'Overview',  path: '/admin/overview',  icon: '📊' },
  { label: 'Sessions',  path: '/admin/sessions',  icon: '🗂' },
  { label: 'Instances', path: '/admin/instances', icon: '⚡' },
  { label: 'Agents',    path: '/admin/agents',    icon: '🤖' },
  { label: 'Channels',  path: '/admin/channels',  icon: '📡' },
  { label: 'Approvals', path: '/admin/approvals', icon: '✅' },
  { label: 'Users',     path: '/admin/users',     icon: '👥' },
  { label: 'Usage',     path: '/admin/usage',     icon: '📈' },
  { label: 'Config',    path: '/admin/config',    icon: '⚙️' },
  { label: 'Debug',     path: '/admin/debug',     icon: '🐛' },
];

interface AppShellProps {
  children: React.ReactNode;
}

function navItemStyle(active: boolean): React.CSSProperties {
  return {
    display: 'flex',
    alignItems: 'center',
    gap: 10,
    width: '100%',
    background: active ? '#eef2ff' : 'transparent',
    border: 'none',
    borderRadius: 8,
    padding: '9px 12px',
    margin: '2px 0',
    cursor: 'pointer',
    fontSize: '0.92rem',
    color: active ? '#4338ca' : '#475569',
    textAlign: 'left' as const,
    fontWeight: active ? 600 : 500,
    transition: 'background 0.12s, color 0.12s',
  };
}

function NavButton({ item, location, navigate }: {
  item: NavItem;
  location: { pathname: string };
  navigate: (path: string) => void;
}) {
  const active = location.pathname === item.path ||
    (item.path !== '/' && location.pathname.startsWith(item.path + '/'));
  const [hover, setHover] = useState(false);
  const style: React.CSSProperties = active
    ? navItemStyle(true)
    : { ...navItemStyle(false), background: hover ? '#f1f5f9' : 'transparent' };
  return (
    <button
      style={style}
      onClick={() => navigate(item.path)}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
    >
      <span style={{ fontSize: '1rem', flexShrink: 0 }}>{item.icon}</span>
      <span>{item.label}</span>
    </button>
  );
}

function UserMenu({ username, onLogout, onSwitchToChat }: {
  username: string;
  onLogout: () => void;
  onSwitchToChat: () => void;
}) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const location = useLocation();

  useEffect(() => {
    function handleClick(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    }
    if (open) document.addEventListener('mousedown', handleClick);
    return () => document.removeEventListener('mousedown', handleClick);
  }, [open]);

  useEffect(() => { setOpen(false); }, [location.pathname]);

  return (
    <div ref={ref} style={{ position: 'relative' as const }}>
      <button
        onClick={() => setOpen(o => !o)}
        style={{
          display: 'flex', alignItems: 'center', gap: 12, width: '100%',
          background: open ? '#f1f5f9' : 'transparent',
          border: `1px solid ${open ? '#cbd5e1' : 'transparent'}`,
          borderRadius: 10, padding: '10px 12px', cursor: 'pointer',
          textAlign: 'left' as const,
          transition: 'background 0.12s, border-color 0.12s',
        }}
      >
        <div style={{
          width: 34, height: 34, borderRadius: '50%', flexShrink: 0,
          background: 'linear-gradient(135deg,#6366f1,#8b5cf6)',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          fontSize: '0.92rem', fontWeight: 700, color: '#ffffff',
          userSelect: 'none' as const,
          boxShadow: '0 2px 6px rgba(99,102,241,0.25)',
        }}>
          {username.charAt(0).toUpperCase() || '?'}
        </div>
        <div style={{ flex: 1, overflow: 'hidden' }}>
          <div style={{ fontSize: '0.92rem', fontWeight: 600, color: '#0f172a', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{username}</div>
          <div style={{ fontSize: '0.76rem', color: '#6366f1', fontWeight: 500 }}>🛡 admin</div>
        </div>
        <span style={{ fontSize: '0.72rem', color: '#94a3b8', transform: open ? 'rotate(180deg)' : 'none', transition: 'transform 0.15s' }}>▲</span>
      </button>

      {open && (
        <div style={{
          position: 'absolute' as const, bottom: 'calc(100% + 8px)', left: 0, right: 0,
          background: '#ffffff', border: '1px solid #e5e7eb', borderRadius: 12,
          boxShadow: '0 12px 32px rgba(15,23,42,0.12), 0 2px 6px rgba(15,23,42,0.06)',
          overflow: 'hidden', zIndex: 100,
        }}>
          <div style={{ padding: '14px 16px 12px', borderBottom: '1px solid #f1f5f9' }}>
            <div style={{ fontSize: '0.92rem', fontWeight: 600, color: '#0f172a' }}>{username}</div>
            <div style={{ fontSize: '0.78rem', color: '#6366f1', marginTop: 2, fontWeight: 500 }}>Administrator</div>
          </div>
          <button
            onClick={() => { onSwitchToChat(); setOpen(false); }}
            style={{
              display: 'flex', alignItems: 'center', gap: 10, width: '100%',
              background: 'transparent', border: 'none', padding: '12px 16px',
              cursor: 'pointer', fontSize: '0.92rem', color: '#475569',
              textAlign: 'left' as const, borderBottom: '1px solid #f1f5f9',
              fontWeight: 500,
            }}
          >
            <span style={{ fontSize: '0.95rem' }}>💬</span>Switch to chat
          </button>
          <button
            onClick={() => { onLogout(); setOpen(false); }}
            style={{
              display: 'flex', alignItems: 'center', gap: 10, width: '100%',
              background: 'transparent', border: 'none', padding: '12px 16px',
              cursor: 'pointer', fontSize: '0.92rem', color: '#dc2626',
              textAlign: 'left' as const,
              fontWeight: 500,
            }}
          >
            <span style={{ fontSize: '0.95rem' }}>↩</span>Sign out
          </button>
        </div>
      )}
    </div>
  );
}

export default function AdminAppShell({ children }: AppShellProps) {
  const navigate = useNavigate();
  const location = useLocation();
  const username = getUsername();

  const currentNav = ADMIN_NAV.find(n =>
    location.pathname === n.path ||
    (n.path !== '/' && location.pathname.startsWith(n.path + '/'))
  );
  const pageTitle = currentNav ? `${currentNav.icon} ${currentNav.label}` : '';

  function logout() {
    clearToken();
    navigate('/login', { replace: true });
  }

  return (
    <div style={{ display: 'flex', height: '100vh', background: '#f5f7fa', color: '#0f172a', overflow: 'hidden' }}>
      <div style={{
        width: 240, background: '#ffffff', borderRight: '1px solid #e5e7eb',
        display: 'flex', flexDirection: 'column', flexShrink: 0, overflowY: 'auto',
      }}>
        <div style={{ padding: '22px 20px 18px', borderBottom: '1px solid #f1f5f9', flexShrink: 0 }}>
          <span style={{ fontWeight: 700, color: '#4f46e5', fontSize: '1.15rem', letterSpacing: '-0.02em', display: 'block' }}>
            ⚙ AgentScope
          </span>
          <span style={{ fontSize: '0.78rem', color: '#94a3b8', marginTop: 4, display: 'block', fontWeight: 500 }}>
            Admin Console
          </span>
        </div>

        <div style={{ padding: '16px 12px 8px' }}>
          <span style={{ fontSize: '0.72rem', fontWeight: 700, letterSpacing: '0.1em', color: '#94a3b8', textTransform: 'uppercase' as const, padding: '0 10px', marginBottom: 8, display: 'block' }}>
            Administration
          </span>
          {ADMIN_NAV.map(item => (
            <NavButton key={item.path} item={item} location={location} navigate={navigate} />
          ))}
        </div>

        <div style={{ flex: 1 }} />

        <div style={{ padding: '12px', borderTop: '1px solid #f1f5f9', flexShrink: 0 }}>
          <UserMenu
            username={username}
            onLogout={logout}
            onSwitchToChat={() => navigate('/chat')}
          />
        </div>
      </div>

      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
        <div style={{
          height: 56, background: '#ffffff', borderBottom: '1px solid #e5e7eb',
          display: 'flex', alignItems: 'center', padding: '0 28px', flexShrink: 0,
        }}>
          <span style={{ fontSize: '1rem', color: '#0f172a', fontWeight: 600, flex: 1 }}>{pageTitle}</span>
          <span style={{ fontSize: '0.8rem', color: '#94a3b8', fontWeight: 500 }}>Admin Console</span>
        </div>
        <div style={{ flex: 1, overflow: 'auto' }}>{children}</div>
      </div>
    </div>
  );
}
