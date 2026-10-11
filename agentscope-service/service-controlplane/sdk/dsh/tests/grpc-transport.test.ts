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

import assert from 'node:assert/strict'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { test } from 'node:test'
import * as grpc from '@grpc/grpc-js'
import * as protoLoader from '@grpc/proto-loader'
import { createLogger } from '../src/logger.ts'
import { GrpcTransport } from '../src/grpc-transport.ts'
import { ContractNotFoundError, type ContractProvider } from '../src/types.ts'

const protoPath = join(dirname(fileURLToPath(import.meta.url)), '..', 'proto', 'asdp.proto')

function loadPkg(): grpc.GrpcObject {
    const def = protoLoader.loadSync(protoPath, {
        keepCase: true,
        longs: Number,
        enums: String,
        defaults: true,
        oneofs: true,
    })
    return grpc.loadPackageDefinition(def)
}

function provider(overrides: Partial<ContractProvider> = {}): ContractProvider {
    return {
        info: () => ({}),
        sessions: () => [],
        sessionState: () => {
            throw new ContractNotFoundError('none')
        },
        context: () => ({}),
        messages: () => ({ messages: [] }),
        subagents: () => [],
        workspaces: () => [],
        tasks: () => ({ tasks: [] }),
        compress: () => undefined,
        terminate: () => undefined,
        abort: () => undefined,
        sessionPhase: () => 'idle',
        ...overrides,
    }
}

test('grpc transport receives SessionCommand and ExecutionAttemptCommand from a fake server', async () => {
    const pkg = loadPkg() as {
        agentscope: { protocol: { v1: { AgentDataPlaneService: { service: grpc.ServiceDefinition } } } }
    }
    const service = pkg.agentscope.protocol.v1.AgentDataPlaneService.service
    const commands: string[] = []
    const events: string[] = []
    const impl = provider({
        abort(sessionId) {
            commands.push(`abort:${sessionId}`)
        },
    })
    const server = new grpc.Server()
    server.addService(service, {
        Connect(call: grpc.ServerDuplexStream<Record<string, unknown>, Record<string, unknown>>) {
            call.on('data', (up: Record<string, unknown>) => {
                if (up.connect) {
                    call.write({
                        connect_ack: { accepted: true, control_plane_version: 'test' },
                    })
                    call.write({
                        session_cmd: {
                            session_id: 'sess-1',
                            command: 'abort',
                            params: Buffer.alloc(0),
                        },
                    })
					call.write({
						execution_attempt: {
							attempt_id: 'a1',
							agent_task_id: 't1',
							run_id: 'r1',
							node_id: 'n1',
							generation: 1,
							command: 'dispatch',
							context_url: '/api/v1/agent-tasks/t1/context',
							task_token: 'task-token',
                            payload: Buffer.from(JSON.stringify({ content: 'claimed' })),
                        },
                    })
                }
            })
        },
    })
    const port = await new Promise<number>((resolve, reject) => {
        server.bindAsync('127.0.0.1:0', grpc.ServerCredentials.createInsecure(), (error, bound) => {
            if (error) {
                reject(error)
                return
            }
            resolve(bound)
        })
    })
	const transport = new GrpcTransport({
		tenant: 'default',
		internalToken: 'internal-token',
        addr: `127.0.0.1:${port}`,
        agentName: 'deepseek-harness',
        namespace: 'default',
        instanceId: 'test-instance',
        runtime: 'deepseek-harness',
        sdkVersion: '0.1.0',
		capabilities: ['agent-task'],
        log: createLogger(),
        provider: impl,
		onExecutionAttempt: (command) => {
			events.push(command.agentTaskId)
        },
    })
    transport.start()
    const deadline = Date.now() + 8_000
    while (Date.now() < deadline && (commands.length === 0 || events.length === 0)) {
        await new Promise((resolve) => setTimeout(resolve, 50))
    }
    try {
        assert.deepEqual(commands, ['abort:sess-1'])
		assert.deepEqual(events, ['t1'])
        assert.equal(transport.isConnected(), true)
    } finally {
        await transport.close()
        server.forceShutdown()
    }
})
