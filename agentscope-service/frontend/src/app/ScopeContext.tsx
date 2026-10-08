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

/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

import { createContext, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api } from '@/lib/apiClient';
import { getToken, me } from '@/lib/auth';
import { useAccountIdentity } from '@/lib/accountIdentity';
import { resolveAuthorizedNamespace, setRequestNamespace, type NamespaceSummary } from '@/lib/namespaceScope';
import { useQueryClient } from '@tanstack/react-query';

const TENANT_KEY = 'aistio.console.tenant';
const NAMESPACE_KEY = 'aistio.console.namespace';

type ControlPlaneScope = {
  namespaces: NamespaceSummary[];
  roles: string[];
  refreshNamespaces: () => void;
  tenant: string;
  namespace: string;
  mode: 'single' | 'multi';
  selectorVisible: boolean;
  setScope: (tenant: string, namespace: string) => void;
  scopedPath: (path: string) => string;
};

type ScopeDescriptor = Pick<ControlPlaneScope, 'tenant' | 'namespace' | 'mode' | 'selectorVisible'> & { namespaces?: NamespaceSummary[] };
type ScopeResolution = { token: string | null; descriptor: ScopeDescriptor };

const ScopeContext = createContext<ControlPlaneScope | null>(null);

function stored(key: string): string {
  try {
    return window.localStorage.getItem(key) || '';
  } catch {
    return '';
  }
}

export function ScopeProvider({ children }: { children: ReactNode }) {
  const [params, setParams] = useSearchParams();
  const qc = useQueryClient();
  const [refresh, setRefresh] = useState(0);
  const token = getToken();
  useAccountIdentity();
  const [resolution, setResolution] = useState<ScopeResolution | null>(() => token ? null : ({
    token: null,
    descriptor: { tenant: 'default', namespace: 'default', mode: 'single', selectorVisible: false },
  }));
  const [scopeError, setScopeError] = useState('');
  const authority = useRef('');
  const [authorityVersion, setAuthorityVersion] = useState(0);

  useEffect(() => {
    if (!token) {
      setScopeError('');
      setResolution({
        token: null,
        descriptor: { tenant: 'default', namespace: 'default', mode: 'single', selectorVisible: false },
      });
      return;
    }
    let cancelled = false;
    setScopeError('');
    Promise.all([api.get<ScopeDescriptor>('/api/v1/me/scope'), me()]).then(([scope, account]) => {
      if (cancelled) return;
      const nextAuthority = JSON.stringify({ token, roles: [...account.roles].sort(), namespaces: scope.namespaces?.map(n => [n.tenant, n.name, [...n.roles].sort(), n.accessVersion]) });
      if (authority.current && authority.current !== nextAuthority) {
        void qc.cancelQueries();
        qc.clear();
        setAuthorityVersion(value => value + 1);
      }
      authority.current = nextAuthority;
      const normalized: ScopeDescriptor = {
        namespaces: scope.namespaces,
        tenant: scope.tenant || 'default',
        namespace: scope.namespace || 'default',
        mode: scope.mode === 'multi' ? 'multi' : 'single',
        selectorVisible: scope.mode === 'multi' && scope.selectorVisible !== false,
      };
      if (resolution?.token !== token) qc.clear();
      setResolution({ token, descriptor: normalized });
      if (normalized.mode === 'single' && (params.has('tenant') || params.has('namespace'))) {
        const clean = new URLSearchParams(params);
        clean.delete('tenant');
        clean.delete('namespace');
        setParams(clean, { replace: true });
      }
    }).catch(() => {
      if (!cancelled) {
        setScopeError('Unable to resolve the control-plane scope. Refresh the page or check the server connection.');
      }
    });
    return () => { cancelled = true; };
    // Query scope changes are handled locally in multi mode. Authentication
    // changes force a fresh server-owned scope resolution.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [token, refresh]);

  useEffect(() => {
    if (!token) return;
    const refreshAccess = () => setRefresh(value => value + 1);
    const timer = window.setInterval(refreshAccess, 30000);
    window.addEventListener('focus', refreshAccess);
    return () => { window.clearInterval(timer); window.removeEventListener('focus', refreshAccess); };
  }, [token]);

  const descriptor = resolution?.token === token ? resolution.descriptor : null;

  const mode = descriptor?.mode ?? 'single';
  const requestedTenant = mode === 'single'
    ? descriptor?.tenant || 'default'
    : params.get('tenant') || stored(TENANT_KEY) || descriptor?.tenant || 'default';
  const requestedNamespace = mode === 'single'
    ? descriptor?.namespace || 'default'
    : params.get('namespace') || stored(NAMESPACE_KEY) || descriptor?.namespace || 'default';

  const authorized = descriptor?.namespaces ? resolveAuthorizedNamespace(descriptor.namespaces, requestedTenant, requestedNamespace, descriptor.namespace) : undefined;
  const tenant = authorized?.tenant ?? requestedTenant;
  const namespace = authorized?.name ?? requestedNamespace;
  setRequestNamespace(tenant, namespace, token);
  useEffect(() => {
    if (!descriptor?.namespaces || !authorized || mode !== 'multi') return;
    if (params.get('tenant') === tenant && params.get('namespace') === namespace) return;
    const next = new URLSearchParams(params);
    next.set('tenant', tenant);
    next.set('namespace', namespace);
    setParams(next, { replace: true });
  }, [descriptor, authorized, mode, params, tenant, namespace, setParams]);

  const value = useMemo<ControlPlaneScope>(() => ({
    namespaces: descriptor?.namespaces ?? [],
    roles: authorized?.roles ?? [],
    refreshNamespaces: () => setRefresh(v => v + 1),
    tenant,
    namespace,
    mode,
    selectorVisible: descriptor?.selectorVisible ?? false,
    setScope(nextTenant, nextNamespace) {
      if (mode === 'single') return;
      const cleanTenant = nextTenant.trim() || 'default';
      const cleanNamespace = nextNamespace.trim() || 'default';
      if (descriptor?.namespaces && !descriptor.namespaces.some(n => n.tenant === cleanTenant && n.name === cleanNamespace)) return;
      void qc.cancelQueries(); qc.clear();
      try {
        window.localStorage.setItem(TENANT_KEY, cleanTenant);
        window.localStorage.setItem(NAMESPACE_KEY, cleanNamespace);
      } catch {
        // The URL remains the source of truth when browser storage is unavailable.
      }
      const next = new URLSearchParams(params);
      next.set('tenant', cleanTenant);
      next.set('namespace', cleanNamespace);
      setParams(next, { replace: true });
    },
    scopedPath(path) {
      const [pathname, rawQuery = ''] = path.split('?');
      const next = new URLSearchParams(rawQuery);
      if (mode === 'multi') {
        next.set('tenant', tenant);
        next.set('namespace', namespace);
      }
      let productPath = pathname;
      if (!pathname.startsWith('/work') && !pathname.startsWith('/agent-center') && !pathname.startsWith('/operations')) {
        if (pathname.startsWith('/tasks')) {
          productPath = `/agent-center/activity${pathname}`;
        } else if (pathname.startsWith('/orchestration/runs')) {
          productPath = `/agent-center/activity${pathname.replace('/orchestration/runs', '/executions')}`;
        } else if (pathname.startsWith('/sessions')) {
          productPath = `/agent-center/activity${pathname}`;
        } else if (pathname.startsWith('/runtime')) {
          productPath = '/agent-center/agents';
        } else if (pathname.startsWith('/teams') || pathname.startsWith('/orchestration/definitions') || pathname.startsWith('/agents')) {
          productPath = `/agent-center${pathname.replace('/orchestration/definitions', '/workflows')}`;
        } else {
          productPath = `/work${pathname}`;
        }
      }
      const query = next.toString();
      return query ? `${productPath}?${query}` : productPath;
    },
  }), [descriptor, authorized, mode, namespace, params, setParams, tenant, qc]);

  if (scopeError) {
    return <div className="flex h-full items-center justify-center px-6 text-center text-sm text-destructive">{scopeError}</div>;
  }
  if (!descriptor) {
    return <div className="flex h-full items-center justify-center text-sm text-muted-foreground">Loading console…</div>;
  }
  return <ScopeContext.Provider key={`${token}:${tenant}:${namespace}:${authorityVersion}`} value={value}>{children}</ScopeContext.Provider>;
}

export function useControlPlaneScope(): ControlPlaneScope {
  const value = useContext(ScopeContext);
  if (!value) throw new Error('useControlPlaneScope must be used inside ScopeProvider');
  return value;
}
