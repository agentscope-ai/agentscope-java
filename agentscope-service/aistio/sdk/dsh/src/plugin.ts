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

import { resolveConfig } from './config.js'
import { AgentTaskCoordination } from './agent-task-coordination.js'
import { ContractHttpServer } from './contract-http.js'
import { GrpcTransport } from './grpc-transport.js'
import { createLogger } from './logger.js'
import { HttpSelfRegistration } from './registration.js'
import { SessionIndex } from './session-index.js'
import { TranscriptWriter } from './transcript-writer.js'
import {
	CAP_AGENT_TASK,
    CONTRACT_LEVEL,
    ContractBusyError,
    ContractUnsupportedError,
    FRAMEWORK,
    SDK_VERSION,
    type ContractProvider,
    type DshAgent,
    type DshContext,
    type DshSession,
    type DshSessionEvent,
    type PluginConfig,
    type ResolvedConfig,
} from './types.js'

export const name = 'aistio'
export const inject = ['sessions', 'agents']

export interface BridgeHandle {
    port: number
    close(): Promise<void>
}

/**
 * Mount the aistio contract HTTP server, observe DSH sessions, and optionally
 * self-register with aistiod.
 */
export function apply(ctx: DshContext, raw: PluginConfig = {}): void {
    const handlePromise = startBridge(ctx, raw)
    void handlePromise.catch((error: unknown) => {
        createLogger(ctx.logger).error(
            `failed to start aistio bridge: ${error instanceof Error ? error.message : String(error)}`,
        )
    })
    onDispose(ctx, () => {
        void handlePromise.then((handle) => handle.close()).catch(() => undefined)
    })
}

/**
 * Test- and host-facing startup. `apply` fires this in the background.
 */
export async function startBridge(ctx: DshContext, raw: PluginConfig = {}): Promise<BridgeHandle> {
    const config = resolveConfig(raw)
    const log = createLogger(ctx.logger)
    const index = new SessionIndex(ctx)
    index.attach()

	const agentTasks = config.internalToken ? new AgentTaskCoordination(ctx, config, log) : undefined

    const transcript =
        config.transcriptDir !== ''
            ? new TranscriptWriter(
                  config.transcriptDir,
                  config.namespace || 'default',
                  config.agentName,
                  config.instanceId,
                  log,
              )
            : undefined
    if (transcript) {
        ctx.on('session/event', (session, event) => {
            void transcript.onEvent(session as DshSession | undefined, event as DshSessionEvent | undefined)
        })
    }

    const boundPort = () => server?.getPort() ?? config.contractPort
	const provider = createProvider(ctx, config, index, boundPort)

    let server: ContractHttpServer | undefined
    if (config.startHttp) {
        server = new ContractHttpServer(
            config.contractHost,
            config.contractPort,
            provider,
            config.internalToken,
        )
    }

    if (server) {
        try {
            await server.start()
        } catch (error) {
            index.detach()
			agentTasks?.close()
            throw error
        }
    }

	const extraCaps = index.capabilities(agentTasks ? [CAP_AGENT_TASK] : [])

    let registration: HttpSelfRegistration | undefined
    const shouldRegister =
        config.startHttpRegister && config.internalToken !== '' && server !== undefined
    if (config.startHttpRegister && !config.internalToken) {
        log.warn(
            `BUILDER_INTERNAL_TOKEN is blank; contract HTTP on :${server?.getPort() ?? config.contractPort} but not registering with ${config.controlHttp}`,
        )
    }
    if (shouldRegister && server) {
        const baseUrl = config.publicBaseUrl || `http://127.0.0.1:${server.getPort()}`
        registration = new HttpSelfRegistration({
			tenant: config.tenant,
            controlHttp: config.controlHttp,
            internalToken: config.internalToken,
            agentName: config.agentName,
            namespace: config.namespace,
            instanceId: config.instanceId,
            baseUrl,
            runtime: FRAMEWORK,
            framework: FRAMEWORK,
            contractLevel: CONTRACT_LEVEL,
            capabilities: extraCaps,
            heartbeatIntervalMs: config.heartbeatIntervalMs,
            log,
        })
        registration.start()
        log.info(
			`instrumented DeepSeek Harness as '${config.agentName}' (contract :${server.getPort()}, control ${config.controlHttp}, agent-task=${Boolean(agentTasks)})`,
        )
    }

    let grpc: GrpcTransport | undefined
    if (config.startGrpc && config.internalToken) {
        grpc = new GrpcTransport({
			tenant: config.tenant,
			internalToken: config.internalToken,
            addr: config.controlGrpc,
            agentName: config.agentName,
            namespace: config.namespace,
            instanceId: config.instanceId,
            runtime: FRAMEWORK,
            sdkVersion: SDK_VERSION,
            capabilities: extraCaps,
            log,
            provider,
			onExecutionAttempt: (command) => {
				grpc?.reportExecutionAttempt({ attempt_id: command.attemptId, agent_task_id: command.agentTaskId, run_id: command.runId, node_id: command.nodeId, generation: command.generation, action: 'ack', attempt_token: command.attemptToken })
				if (command.command === 'cancel') {
					grpc?.reportExecutionAttempt({ attempt_id: command.attemptId, agent_task_id: command.agentTaskId, run_id: command.runId, node_id: command.nodeId, generation: command.generation, action: 'cancelled', attempt_token: command.attemptToken })
					return
				}
				grpc?.reportExecutionAttempt({ attempt_id: command.attemptId, agent_task_id: command.agentTaskId, run_id: command.runId, node_id: command.nodeId, generation: command.generation, action: 'start', attempt_token: command.attemptToken })
				void agentTasks?.accept(command.agentTaskId, command.contextUrl, command.taskToken, command.payload, command.attemptId, command.runId, command.nodeId).catch((error) => log.warn(`ExecutionAttempt ${command.attemptId} dispatch failed`, error))
			},
        })
        grpc.start()
        const reportTimer = setInterval(() => {
            try {
                grpc?.reportSessions(index.snapshots())
            } catch {
                // bypass
            }
        }, config.heartbeatIntervalMs)
        reportTimer.unref?.()
        onDispose(ctx, () => clearInterval(reportTimer))
        log.info(`ASDP gRPC client targeting ${config.controlGrpc} (failure will not stop the agent loop)`)
    }

    return {
        port: server?.getPort() ?? 0,
        async close() {
            await grpc?.close()
            await registration?.close()
            server?.close()
			agentTasks?.close()
            index.detach()
        },
    }
}

function createProvider(
    ctx: DshContext,
    config: ResolvedConfig,
    index: SessionIndex,
	boundPort: () => number,
): ContractProvider {
    return {
        info() {
			const extra = config.internalToken ? [CAP_AGENT_TASK] : []
            return {
                name: config.agentName,
                runtime: FRAMEWORK,
                version: SDK_VERSION,
                sdkVersion: SDK_VERSION,
                contractLevel: CONTRACT_LEVEL,
                capabilities: index.capabilities(extra),
                port: boundPort(),
                agentConfig: {
                    modelProvider: 'deepseek',
                    model: index.pickRootAgent()?.options?.model ?? '',
                },
            }
        },
        sessions: () => index.snapshots(),
        sessionState: (sessionId) => index.sessionState(sessionId),
        context: (sessionId) => index.context(sessionId),
        messages: (sessionId, offset, limit) => index.messages(sessionId, offset, limit),
        subagents: () => index.subagents(),
        workspaces: () => index.workspaces(),
        tasks: (sessionId) => index.tasks(sessionId),
        subagentTasks: (sessionId) => {
            if (!index.hasSubagents()) {
                throw new ContractUnsupportedError('subagent-task-query is not supported')
            }
            return index.subagentTasks(sessionId)
        },
        cancelSubagentTask: (sessionId, taskId) => {
            if (!index.hasSubagents()) {
                throw new ContractUnsupportedError('subagent-task-command is not supported')
            }
            index.cancelSubagentTask(sessionId, taskId)
        },
        planMode: (sessionId, body) => {
            if (!index.hasPlanMode()) {
                throw new ContractUnsupportedError('plan-mode is not supported')
            }
            index.planMode(sessionId, body)
        },
        exportTranscript: (sessionId) => index.exportTranscript(sessionId),
        async postMessage(sessionId, body) {
            const agent = index.requireAgent(sessionId)
            if (agent.status === 'running' || index.require(sessionId).busy) {
                throw new ContractBusyError('session is busy')
            }
            const content = parseMessageContent(body)
            if (!content) {
                throw new Error('content is required')
            }
            agent.followup(userFollowup(content))
        },
        async compress(sessionId) {
            const agent = index.requireAgent(sessionId)
            if (agent.status === 'running' || index.require(sessionId).busy) {
                throw new ContractBusyError('session is busy')
            }
            const compaction = ctx.get?.('compaction') as
                | { compactNow?(agent: DshAgent, signal: AbortSignal): Promise<unknown> }
                | undefined
            if (!compaction?.compactNow) {
                throw new ContractUnsupportedError('session-command compress is not supported')
            }
            index.setPhase(sessionId, 'compressing')
            try {
                await compaction.compactNow(agent, new AbortController().signal)
            } finally {
                index.setPhase(sessionId, agent.status === 'running' ? 'active' : 'idle')
            }
        },
        abort(sessionId) {
            const agent = index.requireAgent(sessionId)
            agent.cancel({ kind: 'hook', reason: 'aistio abort' }, { keepInbox: true })
        },
        terminate(sessionId) {
            const agent = index.requireAgent(sessionId)
            agent.cancel({ kind: 'hook', reason: 'aistio terminate' })
            index.setPhase(sessionId, 'terminated')
        },
        sessionPhase(sessionId) {
            try {
                return index.sessionState(sessionId).phase as string
            } catch {
                return 'idle'
            }
        },
    }
}

function parseMessageContent(body: Buffer): string {
    if (!body || body.length === 0) {
        return ''
    }
    const root = JSON.parse(body.toString('utf8')) as { content?: unknown; text?: unknown }
    return String(root.content ?? root.text ?? '').trim()
}

function userFollowup(text: string): Record<string, unknown> {
    return {
        id: crypto.randomUUID(),
        role: 'user',
        content: [{ type: 'text', text }],
        source: { kind: 'user' },
    }
}

function onDispose(ctx: DshContext, cleanup: () => void): void {
    if (typeof ctx.effect === 'function') {
        ctx.effect(() => cleanup)
        return
    }
    ctx.on('dispose', cleanup)
}
