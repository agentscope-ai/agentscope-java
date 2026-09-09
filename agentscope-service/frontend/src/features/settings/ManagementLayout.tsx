import { NavLink, Outlet } from 'react-router-dom';
import { isAdmin } from '@/lib/auth';
import { useAccountIdentity } from '@/lib/accountIdentity';

export default function ManagementLayout() {
  useAccountIdentity();
  const links = [{ to: '/settings/namespaces', label: 'Namespaces' }, ...(isAdmin() ? [{ to: '/settings/users', label: 'Users' }, { to: '/settings/access-log', label: 'Access log' }, { to: '/settings/integrations', label: 'Integrations' }] : [])];
  return <div className="min-h-full bg-white"><div className="border-b px-5 pt-5 sm:px-8 lg:px-10"><p className="text-xs font-semibold uppercase tracking-wider text-slate-400">Namespaces & access</p><nav aria-label="Access settings" className="mt-3 flex gap-6 overflow-x-auto">{links.map(link => <NavLink key={link.to} to={link.to} className={({ isActive }) => `whitespace-nowrap border-b-2 pb-3 text-sm font-medium ${isActive ? 'border-indigo-600 text-indigo-700' : 'border-transparent text-slate-500 hover:text-slate-900'}`}>{link.label}</NavLink>)}</nav></div><Outlet /></div>;
}
