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

import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import * as grpc from '@grpc/grpc-js'
import * as protoLoader from '@grpc/proto-loader'
import type { Logger } from './logger.js'
import type { ContractProvider } from './types.js'

const SEND_QUEUE_SIZE = 256
const HEARTBEAT_INTERVAL_MS = 15_000
const BACKOFF_INITIAL_MS = 500
const BACKOFF_MAX_MS = 30_000

export interface GrpcTransportOptions {
	tenant: string
	internalToken: string
    addr: string
    agentName: string
    namespace: string
    instanceId: string
    runtime: string
    sdkVersion: string
    capabilities: string[]
    log: Logger
    provider: ContractProvider
	onExecutionAttempt?: (command: ExecutionAttemptCommand) => void
}

export interface ExecutionAttemptCommand {
	attemptId: string
	agentTaskId: string
	runId: string
	nodeId: string
	generation: number
	command: string
	contextUrl: string
	taskToken: string
	attemptToken: string
	payload: Buffer
}

interface UpstreamMeta {
	tenant: string
    agent_name: string
    instance_id: string
    namespace: string
    timestamp: number
}

/**
 * ASDP bidirectional gRPC client. Connection failure only logs a warning —
 * it never takes down the DSH agent loop.
 */
export class GrpcTransport {
    private readonly queue: Array<Record<string, unknown>> = []
    private stop = false
    private connected = false
    private dropped = 0
    private loop?: Promise<void>
    private client?: grpc.Client
    private stream?: grpc.ClientDuplexStream<Record<string, unknown>, Record<string, unknown>>
    private lastContextHash = new Map<string, string>()

    constructor(private readonly options: GrpcTransportOptions) {}

    start(): void {
        if (this.loop) {
            return
        }
        this.stop = false
        this.loop = this.run()
    }

    async close(): Promise<void> {
        this.stop = true
        try {
            this.stream?.cancel()
        } catch {
            // ignore
        }
        try {
            this.stream?.end()
        } catch {
            // ignore
        }
        try {
            this.client?.close()
        } catch {
            // ignore
        }
        const loop = this.loop
        this.loop = undefined
        if (loop) {
            await Promise.race([loop.catch(() => undefined), sleep(1_000)])
        }
        this.connected = false
    }

    isConnected(): boolean {
        return this.connected
    }

    droppedCount(): number {
        return this.dropped
    }

    reportSessions(snapshots: Array<Record<string, unknown>>): void {
        this.enqueue({
            meta: this.meta(),
            session_report: { sessions: snapshots.map(toProtoSnapshot) },
        })
    }

    reportContext(sessionId: string, hash: string, payload: Record<string, unknown>): void {
        if (this.lastContextHash.get(sessionId) === hash) {
            return
        }
        this.lastContextHash.set(sessionId, hash)
        this.enqueue({
            meta: this.meta(),
            context_report: {
                session_id: sessionId,
                context_hash: hash,
                captured_at: Date.now(),
                system_prompt: String(payload.systemPrompt ?? ''),
                is_compacted: Boolean(payload.isCompacted),
                total_tokens: Number(payload.totalTokens ?? 0),
                max_tokens: Number(payload.maxTokens ?? 0),
                framework: String(payload.framework ?? ''),
            },
        })
    }

	reportExecutionAttempt(report: Record<string, unknown>): void {
		this.enqueue({ meta: this.meta(), execution_attempt: report })
	}

    private enqueue(msg: Record<string, unknown>): void {
        if (this.queue.length >= SEND_QUEUE_SIZE) {
            this.dropped += 1
            return
        }
        this.queue.push(msg)
    }

    private meta(): UpstreamMeta {
        return {
			tenant: this.options.tenant,
            agent_name: this.options.agentName,
            instance_id: this.options.instanceId,
            namespace: this.options.namespace,
            timestamp: Date.now(),
        }
    }

    private async run(): Promise<void> {
        let backoff = BACKOFF_INITIAL_MS
        while (!this.stop) {
            try {
                await this.connectOnce()
                backoff = BACKOFF_INITIAL_MS
            } catch (error) {
                this.connected = false
                if (!this.stop) {
                    this.options.log.warn(
                        `ASDP gRPC ${this.options.addr} unavailable: ${
                            error instanceof Error ? error.message : String(error)
                        }`,
                    )
                }
            }
            if (this.stop) {
                try {
                    this.stream?.cancel()
                } catch {
                    // ignore
                }
                return
            }
            await sleep(backoff)
            backoff = Math.min(backoff * 2, BACKOFF_MAX_MS)
        }
    }

    private async connectOnce(): Promise<void> {
        const proto = loadService()
        const client = new proto.AgentDataPlaneService(
            this.options.addr,
            grpc.credentials.createInsecure(),
        )
        this.client = client
		const metadata = new grpc.Metadata()
		metadata.set('authorization', `Bearer ${this.options.internalToken}`)
        const stream = client.Connect(metadata)
        this.stream = stream

        await new Promise<void>((resolve, reject) => {
            stream.on('error', (error: Error) => {
                this.connected = false
                reject(error)
            })
            stream.on('end', () => {
                this.connected = false
                resolve()
            })
            stream.on('data', (down: Record<string, unknown>) => {
                try {
                    this.handleDownstream(down)
                } catch {
                    // bypass: never disturb the loop
                }
            })
            stream.write({
                meta: this.meta(),
                connect: {
                    runtime: this.options.runtime,
                    sdk_version: this.options.sdkVersion,
                    capabilities: this.options.capabilities,
                    session_affinity: '',
                },
            })
            void this.pump(stream)
        })
    }

    private async pump(
        stream: grpc.ClientDuplexStream<Record<string, unknown>, Record<string, unknown>>,
    ): Promise<void> {
        let lastHeartbeat = 0
        while (!this.stop && this.stream === stream) {
            const next = this.queue.shift()
            if (next) {
                stream.write(next)
                continue
            }
            const now = Date.now()
            if (now - lastHeartbeat >= HEARTBEAT_INTERVAL_MS) {
                lastHeartbeat = now
                stream.write({
                    meta: this.meta(),
                    heartbeat: { timestamp: now },
                })
            }
            await sleep(250)
        }
        try {
            stream.cancel()
        } catch {
            // ignore
        }
    }

    private handleDownstream(down: Record<string, unknown>): void {
        const connectAck = (down.connect_ack ?? down.connectAck) as
            | { accepted?: boolean; control_plane_version?: string; controlPlaneVersion?: string }
            | undefined
        if (connectAck) {
            if (connectAck.accepted !== false) {
                this.connected = true
            }
            return
        }
        const cmd = (down.session_cmd ?? down.sessionCmd) as
            | { session_id?: string; sessionId?: string; command?: string; params?: Uint8Array | Buffer | string }
            | undefined
		if (cmd?.command) {
            void this.dispatchCommand(
                String(cmd.session_id ?? cmd.sessionId ?? ''),
                String(cmd.command),
                toBuffer(cmd.params),
            )
			return
		}
		const attempt = (down.execution_attempt ?? down.executionAttempt) as Record<string, unknown> | undefined
		if (attempt) {
			this.options.onExecutionAttempt?.({
				attemptId: String(attempt.attempt_id ?? attempt.attemptId ?? ''),
				agentTaskId: String(attempt.agent_task_id ?? attempt.agentTaskId ?? ''),
				runId: String(attempt.run_id ?? attempt.runId ?? ''),
				nodeId: String(attempt.node_id ?? attempt.nodeId ?? ''),
				generation: Number(attempt.generation ?? 0),
				command: String(attempt.command ?? ''),
				contextUrl: String(attempt.context_url ?? attempt.contextUrl ?? ''),
				taskToken: String(attempt.task_token ?? attempt.taskToken ?? ''),
				attemptToken: String(attempt.attempt_token ?? attempt.attemptToken ?? ''),
				payload: toBuffer(attempt.payload as Uint8Array | Buffer | string | undefined),
			})
			return
		}
    }

    private async dispatchCommand(sessionId: string, command: string, params: Buffer): Promise<void> {
        const provider = this.options.provider
        try {
            const verb = command.toLowerCase()
            if (verb === 'abort') {
                await provider.abort(sessionId)
                return
            }
            if (verb === 'compress') {
                await provider.compress(sessionId)
                return
            }
            if (verb === 'terminate') {
                await provider.terminate(sessionId)
            }
        } catch (error) {
            this.options.log.debug(
                `ASDP SessionCommand ${command} failed: ${
                    error instanceof Error ? error.message : String(error)
                }`,
            )
        }
    }
}

type ServiceCtor = new (
    address: string,
    creds: grpc.ChannelCredentials,
) => grpc.Client & {
	Connect(metadata?: grpc.Metadata): grpc.ClientDuplexStream<Record<string, unknown>, Record<string, unknown>>
}

function loadService(): { AgentDataPlaneService: ServiceCtor } {
    const protoPath = join(dirname(fileURLToPath(import.meta.url)), '..', 'proto', 'asdp.proto')
    const def = protoLoader.loadSync(protoPath, {
        keepCase: true,
        longs: Number,
        enums: String,
        defaults: true,
        oneofs: true,
    })
    const pkg = grpc.loadPackageDefinition(def) as Record<string, unknown>
    const ns = pkg.agentscope as Record<string, unknown> | undefined
    const protocol = ns?.protocol as Record<string, unknown> | undefined
    const v1 = (protocol?.v1 ?? ns) as Record<string, unknown> | undefined
    const service = (v1?.AgentDataPlaneService ??
        (pkg as { AgentDataPlaneService?: ServiceCtor }).AgentDataPlaneService) as ServiceCtor
    if (!service) {
        throw new Error('failed to load AgentDataPlaneService from asdp.proto')
    }
    return { AgentDataPlaneService: service }
}

function toBuffer(value: Uint8Array | Buffer | string | undefined): Buffer {
    if (!value) {
        return Buffer.alloc(0)
    }
    if (typeof value === 'string') {
        return Buffer.from(value)
    }
    return Buffer.from(value)
}

function toProtoSnapshot(snapshot: Record<string, unknown>): Record<string, unknown> {
    const usage = (snapshot.tokenUsage ?? {}) as Record<string, number>
    return {
        session_id: String(snapshot.id ?? snapshot.sessionId ?? ''),
        phase: String(snapshot.phase ?? ''),
        message_count: Number(snapshot.messageCount ?? 0),
        prompt_tokens: Number(usage.promptTokens ?? 0),
        completion_tokens: Number(usage.completionTokens ?? 0),
        context_pressure: Number(snapshot.contextPressure ?? 0),
        framework: String(snapshot.framework ?? ''),
        context_hash: String(snapshot.contextHash ?? ''),
        is_compacted: Boolean(snapshot.isCompacted),
        effective_message_count: Number(snapshot.effectiveMessageCount ?? 0),
    }
}

function sleep(ms: number): Promise<void> {
    return new Promise((resolve) => setTimeout(resolve, ms))
}
