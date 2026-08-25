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

import { Link } from 'react-router-dom';
import type { OrphanSession, StaleDataplane } from '../api';
import { sessionDetailPath } from '../api';

export function HealthBanner({
  staleDataplanes = [],
  orphanSessions = [],
}: {
  staleDataplanes?: StaleDataplane[];
  orphanSessions?: OrphanSession[];
}) {
  const issues = staleDataplanes.length + orphanSessions.length;
  if (issues === 0) return null;

  return (
    <div className="space-y-4 rounded-xl border border-amber-200 bg-amber-50/80 p-5 text-sm">
      <div className="text-base font-semibold text-amber-900">Fleet health alerts</div>

      {staleDataplanes.length > 0 && (
        <div>
          <div className="mb-1.5 text-[13px] font-medium uppercase tracking-wide text-amber-800">
            Stale dataplanes ({staleDataplanes.length})
          </div>
          <ul className="space-y-1.5 text-amber-900/90">
            {staleDataplanes.slice(0, 5).map((dp) => (
              <li key={dp.instanceId}>
                <Link
                  to={`/operate/agents/${encodeURIComponent(dp.agentName)}?namespace=${encodeURIComponent(dp.namespace || 'default')}`}
                  className="hover:underline"
                >
                  {dp.agentName}
                </Link>
                <span className="text-amber-700/80"> · {dp.instanceId}</span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {orphanSessions.length > 0 && (
        <div>
          <div className="mb-1.5 text-[13px] font-medium uppercase tracking-wide text-amber-800">
            Orphan sessions ({orphanSessions.length})
          </div>
          <ul className="space-y-1.5 text-amber-900/90">
            {orphanSessions.slice(0, 5).map((s) => (
              <li key={s.sessionId}>
                <Link to={sessionDetailPath(s)} className="hover:underline">
                  {s.sessionId}
                </Link>
                <span className="text-amber-700/80">
                  {' '}
                  · {s.agentName}
                  {s.instanceRef ? ` · ${s.instanceRef}` : ''}
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
