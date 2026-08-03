import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { EmptyState } from '@/components/EmptyState';
import { Page, PageHeader } from '@/components/Page';
import { listTeams, teamPhaseTone } from '@/api/teams';

export default function TeamsHubPage() {
  const teams = useQuery({
    queryKey: ['teams', 'list'],
    queryFn: () => listTeams(),
    refetchInterval: 10_000,
  });

  const items = teams.data?.items || [];

  return (
    <Page>
      <PageHeader
        title="Teams"
        description="Store-backed Agent Teams. Refresh is read-only and will not recreate teams."
        actions={
          <Button asChild>
            <Link to="/teams/new">New team</Link>
          </Button>
        }
      />

      {teams.isLoading && <p className="text-sm text-muted-foreground">Loading…</p>}
      {teams.isError && (
        <p className="text-sm text-red-600">
          Failed to load teams. Is the control-plane store enabled?
        </p>
      )}

      {!teams.isLoading && items.length === 0 && (
        <EmptyState
          title="No teams"
          description="Create a team with a lead and workers to coordinate shared tasks."
          action={
            <Button asChild>
              <Link to="/teams/new">Create team</Link>
            </Button>
          }
        />
      )}

      {items.length > 0 && (
        <div className="overflow-hidden rounded-xl border border-border bg-white shadow-sm">
          <table className="w-full text-left text-sm">
            <thead className="border-b border-border bg-muted/40 text-muted-foreground">
              <tr>
                <th className="px-4 py-3 font-medium">Name</th>
                <th className="px-4 py-3 font-medium">Namespace</th>
                <th className="px-4 py-3 font-medium">Phase</th>
                <th className="px-4 py-3 font-medium">Lead</th>
                <th className="px-4 py-3 font-medium">Objective</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {items.map((t) => (
                <tr key={`${t.namespace}/${t.name}`} className="hover:bg-muted/30">
                  <td className="px-4 py-3">
                    <Link
                      className="font-medium text-primary hover:underline"
                      to={`/teams/${encodeURIComponent(t.name)}?namespace=${encodeURIComponent(t.namespace || 'default')}`}
                    >
                      {t.name}
                    </Link>
                  </td>
                  <td className="px-4 py-3 text-muted-foreground">{t.namespace}</td>
                  <td className="px-4 py-3">
                    <Badge tone={teamPhaseTone(t.phase)}>{t.phase || '—'}</Badge>
                  </td>
                  <td className="px-4 py-3 font-mono text-xs">{t.leadRef || '—'}</td>
                  <td className="max-w-xs truncate px-4 py-3 text-muted-foreground">
                    {t.objective}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Page>
  );
}
