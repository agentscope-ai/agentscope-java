import { Link } from 'react-router-dom';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { PressureGauge } from '@/components/PressureGauge';
import type { AgentUsage } from '../api';

export function TopAgentsTable({ agents = [] }: { agents?: AgentUsage[] }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>Top agents</CardTitle>
        <CardDescription>Token usage over the last 24 hours</CardDescription>
      </CardHeader>
      <CardContent>
        {agents.length === 0 ? (
          <p className="text-sm text-muted-foreground">No agent usage yet.</p>
        ) : (
          <div className="overflow-hidden rounded-lg border border-border">
            <table className="w-full text-left text-sm">
              <thead className="border-b border-border bg-muted/50 text-[13px] uppercase tracking-wide text-muted-foreground">
                <tr>
                  <th className="px-4 py-3 font-medium">Agent</th>
                  <th className="px-4 py-3 font-medium">Tokens</th>
                  <th className="px-4 py-3 font-medium">Active</th>
                  <th className="px-4 py-3 font-medium">Pressure</th>
                  <th className="px-4 py-3 font-medium">Errors</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {agents.map((a) => (
                  <tr key={`${a.namespace}/${a.agentName}`} className="hover:bg-muted/40">
                    <td className="px-4 py-3">
                      <Link
                        className="font-medium text-primary hover:underline"
                        to={`/operate/agents/${encodeURIComponent(a.agentName)}?namespace=${encodeURIComponent(a.namespace || 'default')}`}
                      >
                        {a.agentName}
                      </Link>
                      <div className="text-sm text-muted-foreground">{a.namespace}</div>
                    </td>
                    <td className="px-4 py-3 font-mono tabular-nums">{(a.totalTokens || 0).toLocaleString()}</td>
                    <td className="px-4 py-3 font-mono tabular-nums">{a.activeSessions ?? 0}</td>
                    <td className="px-4 py-3">
                      <PressureGauge value={a.avgPressure} />
                    </td>
                    <td className="px-4 py-3 font-mono tabular-nums text-muted-foreground">{a.errorCount ?? 0}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </CardContent>
    </Card>
  );
}
