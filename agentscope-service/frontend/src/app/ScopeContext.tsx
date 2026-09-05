/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useSearchParams } from 'react-router-dom';
import { api } from '@/lib/apiClient';
import { getToken } from '@/lib/auth';

const TENANT_KEY = 'aistio.console.tenant';
const NAMESPACE_KEY = 'aistio.console.namespace';

type ControlPlaneScope = {
  tenant: string;
  namespace: string;
  mode: 'single' | 'multi';
  selectorVisible: boolean;
  setScope: (tenant: string, namespace: string) => void;
  scopedPath: (path: string) => string;
};

type ScopeDescriptor = Pick<ControlPlaneScope, 'tenant' | 'namespace' | 'mode' | 'selectorVisible'>;
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
  const token = getToken();
  const [resolution, setResolution] = useState<ScopeResolution | null>(() => token ? null : ({
    token: null,
    descriptor: { tenant: 'default', namespace: 'default', mode: 'single', selectorVisible: false },
  }));
  const [scopeError, setScopeError] = useState('');

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
    api.get<ScopeDescriptor>('/api/v1/me/scope').then((scope) => {
      if (cancelled) return;
      const normalized: ScopeDescriptor = {
        tenant: scope.tenant || 'default',
        namespace: scope.namespace || 'default',
        mode: scope.mode === 'multi' ? 'multi' : 'single',
        selectorVisible: scope.mode === 'multi' && scope.selectorVisible !== false,
      };
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
  }, [token]);

  const descriptor = resolution?.token === token ? resolution.descriptor : null;

  const mode = descriptor?.mode ?? 'single';
  const tenant = mode === 'single'
    ? descriptor?.tenant || 'default'
    : params.get('tenant') || stored(TENANT_KEY) || descriptor?.tenant || 'default';
  const namespace = mode === 'single'
    ? descriptor?.namespace || 'default'
    : params.get('namespace') || stored(NAMESPACE_KEY) || descriptor?.namespace || 'default';

  const value = useMemo<ControlPlaneScope>(() => ({
    tenant,
    namespace,
    mode,
    selectorVisible: descriptor?.selectorVisible ?? false,
    setScope(nextTenant, nextNamespace) {
      if (mode === 'single') return;
      const cleanTenant = nextTenant.trim() || 'default';
      const cleanNamespace = nextNamespace.trim() || 'default';
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
  }), [descriptor?.selectorVisible, mode, namespace, params, setParams, tenant]);

  if (scopeError) {
    return <div className="flex h-full items-center justify-center px-6 text-center text-sm text-destructive">{scopeError}</div>;
  }
  if (!descriptor) {
    return <div className="flex h-full items-center justify-center text-sm text-muted-foreground">Loading console…</div>;
  }
  return <ScopeContext.Provider value={value}>{children}</ScopeContext.Provider>;
}

export function useControlPlaneScope(): ControlPlaneScope {
  const value = useContext(ScopeContext);
  if (!value) throw new Error('useControlPlaneScope must be used inside ScopeProvider');
  return value;
}
