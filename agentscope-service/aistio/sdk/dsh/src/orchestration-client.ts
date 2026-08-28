import { ControlPlaneHttpClient, encodePath } from './control-plane.js'

/** Operator-facing client for immutable definitions and observable runs. */
export class OrchestrationClient {
    constructor(private readonly http: ControlPlaneHttpClient, readonly tenant = 'default', readonly namespace = 'default') {}

    definitions() { return this.get(this.scope('/api/v1/orchestration-definitions')) }
    definition(id: string) { return this.get(`/api/v1/orchestration-definitions/${encodePath(id)}`) }
    createDefinition(body: unknown) { return this.send('POST', '/api/v1/orchestration-definitions', body) }
    updateDefinition(id: string, body: unknown) { return this.send('PATCH', `/api/v1/orchestration-definitions/${encodePath(id)}`, body) }
    validateDefinition(id: string, spec?: unknown) { return this.send('POST', `/api/v1/orchestration-definitions/${encodePath(id)}/validate`, spec === undefined ? {} : { spec }) }
    publishDefinition(id: string) { return this.send('POST', `/api/v1/orchestration-definitions/${encodePath(id)}/publish`, {}) }
    revisions(id: string) { return this.get(`/api/v1/orchestration-definitions/${encodePath(id)}/revisions`) }
    start(id: string, request: unknown) { return this.send('POST', `/api/v1/orchestration-definitions/${encodePath(id)}/runs`, request) }

    runs(issueId?: string) { return this.get(this.scope('/api/v1/orchestration-runs', issueId ? { issueId } : {})) }
    run(id: string) { return this.get(`/api/v1/orchestration-runs/${encodePath(id)}`) }
    graph(id: string) { return this.get(`/api/v1/orchestration-runs/${encodePath(id)}/graph`) }
    events(id: string, after = 0) { return this.get(`/api/v1/orchestration-runs/${encodePath(id)}/events?after=${after}`) }
    control(id: string, action: 'pause' | 'resume' | 'cancel') { return this.send('POST', `/api/v1/orchestration-runs/${encodePath(id)}/${action}`, {}) }
    rerun(id: string, idempotencyKey: string, input?: unknown) { return this.send('POST', `/api/v1/orchestration-runs/${encodePath(id)}/rerun`, input === undefined ? { idempotencyKey } : { idempotencyKey, input }) }
    signal(id: string, name: string, idempotencyKey: string, payload?: unknown) { return this.send('POST', `/api/v1/orchestration-runs/${encodePath(id)}/signals/${encodePath(name)}`, { idempotencyKey, payload }) }

    attempts(taskId?: string) { return this.get(this.scope('/api/v1/execution-attempts', taskId ? { taskId } : {})) }
    attempt(id: string) { return this.get(`/api/v1/execution-attempts/${encodePath(id)}`) }
    runtimePolicy(agentId: string) { return this.get(this.scope(`/api/v1/agent-runtime-policies/${encodePath(agentId)}`)) }
    putRuntimePolicy(agentId: string, policy: unknown) { return this.send('PUT', `/api/v1/agent-runtime-policies/${encodePath(agentId)}`, policy) }

    private scope(path: string, extra: Record<string, string> = {}) {
        return path + '?' + new URLSearchParams({ tenant: this.tenant, namespace: this.namespace, ...extra }).toString()
    }
    private get(path: string) { return this.send('GET', path) }
    private async send(method: string, path: string, body?: unknown) {
        const response = await this.http.send(method, path, body)
        if (response.status < 200 || response.status >= 300) throw new Error(`orchestration request failed: HTTP ${response.status}: ${response.body}`)
        return response.json
    }
}
