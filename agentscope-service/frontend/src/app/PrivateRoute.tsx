import { useEffect, useState } from 'react';
import { Navigate } from 'react-router-dom';
import { clearToken, getToken, me } from '@/lib/auth';

export function PrivateRoute({ children }: { children: React.ReactElement }) {
  const token = getToken();
  const [status, setStatus] = useState<'checking' | 'ok' | 'invalid'>(token ? 'checking' : 'invalid');

  useEffect(() => {
    if (!token) return;
    let cancelled = false;
    me().then(
      () => {
        if (!cancelled) setStatus('ok');
      },
      () => {
        if (cancelled) return;
        clearToken();
        setStatus('invalid');
      },
    );
    return () => {
      cancelled = true;
    };
  }, [token]);

  if (status === 'invalid') return <Navigate to="/login" replace />;
  if (status === 'checking') {
    return <div className="flex h-full items-center justify-center text-sm text-muted-foreground">Loading…</div>;
  }
  return children;
}
