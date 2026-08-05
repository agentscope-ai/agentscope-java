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
