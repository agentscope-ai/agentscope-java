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

import { ControlPlaneHttpClient, encodePath } from './control-plane.js'

export class CollaborationHttpError extends Error {
    constructor(readonly operation: string, readonly status: number, readonly body: string) {
        super(`${operation} failed: HTTP ${status}`)
        this.name = 'CollaborationHttpError'
    }
}

export interface MentionTarget { type: 'human' | 'agent' | 'team'; ref: string }

/** Backend-independent Issue/Comment/AgentTask client used by all DSH runtimes. */
export class CollaborationClient {
    constructor(private readonly http: ControlPlaneHttpClient) {}

    issue(issueId: string, token: string) { return this.send('GET', `/api/v1/issues/${encodePath(issueId)}`, undefined, 'issue.get', { 'X-Agent-Task-Token': token }) }
    comments(issueId: string, token: string, query = '') { return this.send('GET', `/api/v1/issues/${encodePath(issueId)}/comments${query}`, undefined, 'issue.comment.list', { 'X-Agent-Task-Token': token }) }
    addComment(issueId: string, token: string, content: string, mentions: MentionTarget[] = [], parentId?: string) { return this.send('POST', `/api/v1/issues/${encodePath(issueId)}/comments`, { content, mentions, parentId }, 'issue.comment.add', { 'X-Agent-Task-Token': token }) }
    createChild(taskId: string, token: string, body: unknown) { return this.taskSend('POST', taskId, 'children', token, body, 'issue.child.create') }
    team(teamId: string, token: string) { return this.send('GET', `/api/v1/teams/${encodePath(teamId)}`, undefined, 'team.get', { 'X-Agent-Task-Token': token }) }
    approval(token: string, body: unknown) { return this.send('POST', '/api/v1/approvals', body, 'approval.request', { 'X-Agent-Task-Token': token }) }
    task(taskId: string, token: string) { return this.send('GET', `/api/v1/agent-tasks/${encodePath(taskId)}`, undefined, 'task.get', { 'X-Agent-Task-Token': token }) }
    taskContext(taskId: string, token: string) { return this.taskSend('GET', taskId, 'context', token, undefined, 'task.context') }
    ack(taskId: string, token: string, inputIds: string[]) { return this.taskSend('POST', taskId, 'ack', token, { inputIds }, 'task.ack') }
    start(taskId: string, token: string, expectedVersion = 0) { return this.taskSend('POST', taskId, 'start', token, { expectedVersion }, 'task.start') }
    progress(taskId: string, token: string, content: string, mentions: MentionTarget[] = []) { return this.taskSend('POST', taskId, 'progress', token, { content, mentions }, 'task.progress') }
    respond(taskId: string, token: string, content: string, mentions: MentionTarget[] = [], parentId?: string) { return this.taskSend('POST', taskId, 'respond', token, { content, mentions, parentId, type: 'result' }, 'task.respond') }
    complete(taskId: string, token: string, body: unknown) { return this.taskSend('POST', taskId, 'complete', token, body, 'task.complete') }
    fail(taskId: string, token: string, body: unknown) { return this.taskSend('POST', taskId, 'fail', token, body, 'task.fail') }
	run(taskId: string, token: string) { return this.taskSend('GET', taskId, 'run', token, undefined, 'run.get') }
	runGraph(taskId: string, token: string) { return this.taskSend('GET', taskId, 'run/graph', token, undefined, 'run.graph') }
	completeRunNode(taskId: string, token: string, output: unknown) { return this.taskSend('POST', taskId, 'run/node/complete', token, { output }, 'run.node.complete') }
	failRunNode(taskId: string, token: string, code: string, message: string) { return this.taskSend('POST', taskId, 'run/node/fail', token, { code, message }, 'run.node.fail') }
	replanRun(taskId: string, token: string, node: unknown) { return this.taskSend('POST', taskId, 'run/replan', token, node, 'run.replan') }
	signalRun(taskId: string, token: string, name: string, idempotencyKey: string, payload: unknown) { return this.taskSend('POST', taskId, `run/signals/${encodePath(name)}`, token, { idempotencyKey, payload }, 'run.signal') }
	runArtifacts(taskId: string, token: string) { return this.taskSend('GET', taskId, 'run/artifacts', token, undefined, 'run.artifacts') }

    async uploadArtifact(taskId: string, token: string, filename: string, bytes: Uint8Array, contentType = 'application/octet-stream', targetType = 'agent-task', targetRef = taskId) {
        const form = new FormData()
        form.set('file', new Blob([bytes as BlobPart], { type: contentType }), filename)
        form.set('sourceTaskId', taskId)
        form.set('targetType', targetType)
        form.set('targetRef', targetRef)
        const response = await this.http.sendBody('POST', '/api/v1/artifacts/uploads', form, { 'X-Agent-Task-Token': token })
        if (response.status < 200 || response.status >= 300) throw new CollaborationHttpError('artifact.upload', response.status, response.body)
        return response.json
    }

    async downloadArtifact(artifactId: string, taskId: string, token: string) {
        const response = await this.http.receiveBytes('POST', `/api/v1/artifacts/${encodePath(artifactId)}/download?taskId=${encodeURIComponent(taskId)}`, { 'X-Agent-Task-Token': token })
        if (response.status < 200 || response.status >= 300) throw new CollaborationHttpError('artifact.download', response.status, new TextDecoder().decode(response.body))
        return response.body
    }

    private taskSend(method: string, taskId: string, action: string, token: string, body: unknown, operation: string) {
        return this.send(method, `/api/v1/agent-tasks/${encodePath(taskId)}/${action}`, body, operation, { 'X-Agent-Task-Token': token })
    }
    private async send(method: string, path: string, body: unknown, operation: string, headers: Record<string,string> = {}) {
        const response = await this.http.send(method, path, body, headers)
        if (response.status < 200 || response.status >= 300) throw new CollaborationHttpError(operation, response.status, response.body)
        return response.json
    }
}
