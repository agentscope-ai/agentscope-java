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

import { NavLink, Outlet } from 'react-router-dom';
import { isAdmin } from '@/lib/auth';
import { useAccountIdentity } from '@/lib/accountIdentity';

export default function ManagementLayout() {
  useAccountIdentity();
  const links = [{ to: '/settings/namespaces', label: 'Namespaces' }, ...(isAdmin() ? [{ to: '/settings/users', label: 'Users' }, { to: '/settings/access-log', label: 'Access log' }, { to: '/settings/integrations', label: 'Integrations' }] : [])];
  return <div className="min-h-full bg-white"><div className="border-b px-5 pt-5 sm:px-8 lg:px-10"><p className="text-xs font-semibold uppercase tracking-wider text-slate-400">Namespaces & access</p><nav aria-label="Access settings" className="mt-3 flex gap-6 overflow-x-auto">{links.map(link => <NavLink key={link.to} to={link.to} className={({ isActive }) => `whitespace-nowrap border-b-2 pb-3 text-sm font-medium ${isActive ? 'border-indigo-600 text-indigo-700' : 'border-transparent text-slate-500 hover:text-slate-900'}`}>{link.label}</NavLink>)}</nav></div><Outlet /></div>;
}
