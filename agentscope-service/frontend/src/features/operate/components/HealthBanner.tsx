import { Link } from 'react-router-dom';
import type { HighPressureSession, OrphanSession, StaleDataplane } from '../api';

export function HealthBanner({
  staleDataplanes = [],
  highPressureSessions = [],
  orphanSessions = [],
}: {
  staleDataplanes?: StaleDataplane[];
  highPressureSessions?: HighPressureSession[];
  orphanSessions?: OrphanSession[];
}) {
  const issues =
    staleDataplanes.length + highPressureSessions.length + orphanSessions.length;
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

      {highPressureSessions.length > 0 && (
        <div>
          <div className="mb-1.5 text-[13px] font-medium uppercase tracking-wide text-amber-800">
            High context pressure ({highPressureSessions.length})
          </div>
          <ul className="space-y-1.5 text-amber-900/90">
            {highPressureSessions.slice(0, 5).map((s) => (
              <li key={s.sessionId}>
                <Link
                  to={`/operate/sessions/${encodeURIComponent(s.sessionId)}`}
                  className="hover:underline"
                >
                  {s.sessionId}
                </Link>
                <span className="text-amber-700/80">
                  {' '}
                  · {s.agentName}
                  {s.contextPressure != null
                    ? ` · ${Math.round(s.contextPressure * 100)}%`
                    : ''}
                </span>
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
                <Link
                  to={`/operate/sessions/${encodeURIComponent(s.sessionId)}`}
                  className="hover:underline"
                >
                  {s.sessionId}
                </Link>
                <span className="text-amber-700/80">
                  {' '}
                  · {s.agentName} · instance missing/unhealthy
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
