import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'

import { listInbox, listIssues, listTasks, listTeams } from '@/api/collaboration'
import { listRuntimeHosts } from '@/api/runtimeControl'
import { useControlPlaneScope } from '@/app/ScopeContext'
import { Page, PageHeader } from '@/components/Page'
import { Badge } from '@/components/ui/badge'

export default function ControlCenterPage() {
  const scope = useControlPlaneScope()
  const issues = useQuery({
    queryKey: ['issues', scope.tenant, scope.namespace],
    queryFn: () => listIssues(scope.tenant, scope.namespace),
  })
  const tasks = useQuery({
    queryKey: ['agent-tasks', scope.tenant, scope.namespace],
    queryFn: () => listTasks(scope.tenant, scope.namespace),
  })
  const teams = useQuery({
    queryKey: ['teams', scope.tenant, scope.namespace],
    queryFn: () => listTeams(scope.tenant, scope.namespace),
  })
  const inbox = useQuery({
    queryKey: ['inbox', scope.tenant, scope.namespace],
    queryFn: () => listInbox(scope.tenant, scope.namespace),
  })
  const hosts = useQuery({
    queryKey: ['runtime-hosts', scope.tenant, scope.namespace],
    queryFn: () => listRuntimeHosts(scope.tenant, scope.namespace),
  })
  const issueItems = issues.data?.items || []
  const taskItems = tasks.data?.items || []
  const cards = [
    ['Open Issues', issueItems.filter((issue) => !['done', 'cancelled'].includes(issue.status)).length, '/issues'],
    ['Active AgentTasks', taskItems.filter((task) => !['completed', 'failed', 'cancelled'].includes(task.status)).length, '/tasks'],
    ['Teams', teams.data?.items.length || 0, '/teams'],
    ['Needs attention', (inbox.data?.items || []).filter((item) => !item.read).length, '/approvals'],
    ['Runtime hosts', hosts.data?.items.length || 0, '/runtime/hosts'],
  ] as const

  return (
    <Page>
      <PageHeader
        title="Issue collaboration control center"
        description="Every execution starts from durable work and every result returns to its discussion."
      />
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-5">
        {cards.map(([label, value, to]) => (
          <Link
            key={label}
            to={scope.scopedPath(to)}
            className="rounded-xl border bg-white p-5 shadow-sm hover:border-indigo-200"
          >
            <div className="text-sm text-muted-foreground">{label}</div>
            <div className="mt-2 text-3xl font-semibold">{value}</div>
          </Link>
        ))}
      </div>
      <section>
        <h2 className="mb-3 text-lg font-semibold">Recent Issues</h2>
        <div className="divide-y overflow-hidden rounded-xl border bg-white">
          {issueItems.slice(0, 8).map((issue) => (
            <Link
              key={issue.id}
              to={scope.scopedPath(`/issues/${issue.id}`)}
              className="flex items-center justify-between p-4 hover:bg-muted/40"
            >
              <div>
                <strong>{issue.title}</strong>
                <div className="text-xs text-muted-foreground">{issue.assigneeRef || 'Unassigned'}</div>
              </div>
              <Badge tone={issue.status === 'blocked' ? 'danger' : issue.status === 'done' ? 'success' : 'warning'}>
                {issue.status}
              </Badge>
            </Link>
          ))}
        </div>
      </section>
    </Page>
  )
}
