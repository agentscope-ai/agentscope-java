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

import crypto from 'node:crypto'
import { CollaborationClient, type MentionTarget } from './collaboration-client.js'
import { ControlPlaneHttpClient } from './control-plane.js'
import { defineTool } from './define-tool.js'
import type { Logger } from './logger.js'
import type { AgentHandle, DshAgent, DshContext, ResolvedConfig } from './types.js'

interface ActiveTask { handle?: AgentHandle; disposeTool?: () => void }

/** Materializes ASDP AgentTask events as isolated DSH sessions. */
export class AgentTaskCoordination {
    private readonly client: CollaborationClient
    private readonly active = new Map<string, ActiveTask>()
    constructor(private readonly ctx: DshContext, config: ResolvedConfig, private readonly log: Logger) {
        this.client = new CollaborationClient(new ControlPlaneHttpClient(config.controlHttp, config.internalToken))
    }

    async accept(taskId: string, contextUrl: string, token: string, payload: Buffer, attemptId = '', runId = '', nodeId = ''): Promise<void> {
        if (!taskId || !token) throw new Error('AgentTask event requires taskId and task token')
        const envelope = await this.client.taskContext(taskId, token) as Record<string, unknown>
        const existing = this.active.get(taskId); existing?.disposeTool?.(); if (existing?.handle) await existing.handle.dispose().catch(() => undefined)
        const handle = await this.ctx.agents.create?.({ sessionId: taskId, meta: { origin: 'execution-attempt', taskId, attemptId, runId, nodeId } })
        const agent = handle?.agent ?? this.ctx.agents.roots?.()[0] ?? this.ctx.agents.list()[0]
        if (!agent) throw new Error(`no DSH agent available for AgentTask ${taskId}`)
        const disposeTool = this.registerTool(agent, taskId, token, envelope)
        this.active.set(taskId, { handle, disposeTool })
        const eventPayload = payload.length ? payload.toString('utf8') : ''
        agent.followup({ id: crypto.randomUUID(), role: 'user', source: { kind: 'system' }, content: [{ type: 'text', text: `AgentTask ${taskId} is ready. Read the authoritative context below, use the collaboration tool for fresh discussion, and finish through task.complete or task.fail.\ncontextUrl=${contextUrl}\n${JSON.stringify(envelope)}\n${eventPayload}` }] })
    }

    close(): void { for (const item of this.active.values()) { item.disposeTool?.(); if (item.handle) void item.handle.dispose().catch(() => undefined) }; this.active.clear() }

    private registerTool(agent: DshAgent, taskId: string, token: string, envelope: Record<string, unknown>): (() => void)|undefined {
        const registry = agent.ctx?.tools ?? this.ctx.get?.('tools') as { register?: (definition: Record<string,unknown>) => () => void }|undefined
        if (!registry?.register) return undefined
        const issueId = String((envelope.issue as Record<string,unknown>|undefined)?.id ?? '')
        return registry.register(defineTool({ name: 'collaboration', description: 'Read/write the authoritative Issue and Run, coordinate Team nodes, and report this AgentTask result.', parameters: { type: 'object', additionalProperties: true, properties: { action: { type: 'string' }, content: { type: 'string' }, parent_id: { type: 'string' }, mentions: { type: 'array', items: { type: 'object' } }, processed_input_ids: { type: 'array', items: { type: 'string' } }, deferred_input_ids: { type: 'array', items: { type: 'string' } }, result: { type: 'object' }, output: {}, node: { type: 'object' }, signal_name: { type: 'string' }, idempotency_key: { type: 'string' }, payload: {}, code: { type: 'string' }, message: { type: 'string' }, child: { type: 'object' } }, required: ['action'] }, execute: async (raw) => {
            const args = (raw ?? {}) as Record<string,unknown>; const action = String(args.action ?? '').toLowerCase(); const mentions = (args.mentions ?? []) as MentionTarget[]
            switch (action) {
                case 'issue.get': return this.client.issue(issueId, token)
                case 'issue.comment.list': return this.client.comments(issueId, token)
                case 'issue.comment.add': return this.client.addComment(issueId, token, String(args.content ?? ''), mentions, optional(args.parent_id))
                case 'issue.child.create': return this.client.createChild(taskId, token, args.child ?? {})
                case 'task.progress': return this.client.progress(taskId, token, String(args.content ?? ''), mentions)
                case 'task.respond': return this.client.respond(taskId, token, String(args.content ?? ''), mentions, optional(args.parent_id))
                case 'task.complete': return this.client.complete(taskId, token, { summary: String(args.content ?? ''), result: args.result ?? {}, processedInputIds: args.processed_input_ids ?? [], deferredInputIds: args.deferred_input_ids ?? [] })
                case 'task.fail': return this.client.fail(taskId, token, { code: String(args.code ?? 'agent_failed'), message: String(args.message ?? '') })
				case 'run.get': return this.client.run(taskId, token)
				case 'run.graph': return this.client.runGraph(taskId, token)
				case 'run.node.complete': return this.client.completeRunNode(taskId, token, args.output ?? args.result ?? {})
				case 'run.node.fail': return this.client.failRunNode(taskId, token, String(args.code ?? 'coordinator_failed'), String(args.message ?? ''))
				case 'run.replan': return this.client.replanRun(taskId, token, args.node ?? {})
				case 'run.signal': return this.client.signalRun(taskId, token, String(args.signal_name ?? ''), String(args.idempotency_key ?? crypto.randomUUID()), args.payload ?? {})
				case 'run.artifacts': return this.client.runArtifacts(taskId, token)
                default: return { error: `unsupported action ${action}` }
            }
        }}))
    }
}
function optional(value: unknown): string|undefined { const out=String(value ?? '').trim(); return out || undefined }
