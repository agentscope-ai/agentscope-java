/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

import { createContext, useContext, useMemo, type ReactNode } from 'react';
import { useSearchParams } from 'react-router-dom';

const TENANT_KEY = 'aistio.console.tenant';
const NAMESPACE_KEY = 'aistio.console.namespace';

type ControlPlaneScope = {
  tenant: string;
  namespace: string;
  setScope: (tenant: string, namespace: string) => void;
  scopedPath: (path: string) => string;
};

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
  const tenant = params.get('tenant') || stored(TENANT_KEY) || 'default';
  const namespace = params.get('namespace') || stored(NAMESPACE_KEY) || 'default';

  const value = useMemo<ControlPlaneScope>(() => ({
    tenant,
    namespace,
    setScope(nextTenant, nextNamespace) {
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
      next.set('tenant', tenant);
      next.set('namespace', namespace);
      let productPath = pathname;
      if (!pathname.startsWith('/work') && !pathname.startsWith('/agent-center') && !pathname.startsWith('/operations')) {
        if (pathname.startsWith('/tasks') || pathname.startsWith('/orchestration/runs') || pathname.startsWith('/sessions') || pathname.startsWith('/runtime')) {
          productPath = `/operations${pathname.replace('/orchestration/runs', '/runs')}`;
        } else if (pathname.startsWith('/teams') || pathname.startsWith('/orchestration/definitions') || pathname.startsWith('/agents')) {
          productPath = `/agent-center${pathname.replace('/orchestration/definitions', '/workflows')}`;
        } else {
          productPath = `/work${pathname}`;
        }
      }
      return `${productPath}?${next.toString()}`;
    },
  }), [namespace, params, setParams, tenant]);

  return <ScopeContext.Provider value={value}>{children}</ScopeContext.Provider>;
}

export function useControlPlaneScope(): ControlPlaneScope {
  const value = useContext(ScopeContext);
  if (!value) throw new Error('useControlPlaneScope must be used inside ScopeProvider');
  return value;
}
