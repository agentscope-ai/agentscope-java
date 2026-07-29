import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { Badge } from '@/components/ui/badge';
import { EmptyState } from '@/components/EmptyState';
import { PressureGauge } from '@/components/PressureGauge';
import { fetchRuntimeSessions } from './api';

export default function OperateSessionsPage() {
  const sessions = useQuery({
    queryKey: ['runtime-sessions'],
    queryFn: () => fetchRuntimeSessions(),
    refetchInterval: 10_000,
  });
  const list = sessions.data?.sessions || [];

  return (
    <div className="mx-auto max-w-6xl space-y-6 p-6">
      <div>
        <h1 className="text-xl font-semibold">Sessions</h1>
        <p className="text-sm text-muted-foreground">Runtime sessions across all managed data planes.</p>
      </div>

      {list.length === 0 ? (
        <EmptyState title="No sessions" description="Waiting for data planes to report sessions." />
      ) : (
        <div className="overflow-hidden rounded-xl border border-border bg-white">
          <table className="w-full text-left text-sm">
            <thead className="border-b border-border bg-muted/50 text-xs uppercase text-muted-foreground">
              <tr>
                <th className="px-4 py-3 font-medium">Agent</th>
                <th className="px-4 py-3 font-medium">Session</th>
                <th className="px-4 py-3 font-medium">Phase</th>
                <th className="px-4 py-3 font-medium">Pressure</th>
                <th className="px-4 py-3 font-medium">Messages</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {list.map((s) => (
                <tr key={s.id} className="hover:bg-muted/40">
                  <td className="px-4 py-3 font-medium">{s.agentName}</td>
                  <td className="px-4 py-3">
                    <Link className="text-primary hover:underline" to={`/operate/sessions/${encodeURIComponent(s.sessionId)}`}>
                      {s.sessionId}
                    </Link>
                  </td>
                  <td className="px-4 py-3">
                    <Badge>{s.phase}</Badge>
                  </td>
                  <td className="px-4 py-3">
                    <PressureGauge value={s.snapshot?.contextPressure} />
                  </td>
                  <td className="px-4 py-3 tabular-nums text-muted-foreground">
                    {s.snapshot?.effectiveMessageCount ?? s.snapshot?.messageCount ?? '—'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
