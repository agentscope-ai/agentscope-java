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

import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http'
import { randomUUID } from 'node:crypto'
import { URL } from 'node:url'
import {
    ContractBusyError,
    ContractNotFoundError,
    ContractUnauthorizedError,
    ContractUnsupportedError,
    INTERNAL_TOKEN_HEADER,
    MAX_SESSIONS_PROBE_PAGE,
    type ContractProvider,
} from './types.js'

const DEFAULT_MESSAGE_LIMIT = 100

/**
 * In-process `/agentscope/*` contract server. Failures map to 401 / 404 / 409 / 501 / 500.
 * Write operations require {@code X-Builder-Internal-Token} when a token is configured.
 * {@code GET /health} and {@code GET /info} stay open for the HTTP prober.
 */
export class ContractHttpServer {
    private server: Server | undefined
    private boundPort = 0

    constructor(
        private readonly host: string,
        private readonly port: number,
        private readonly provider: ContractProvider,
        private readonly internalToken = '',
    ) {}

    getPort(): number {
        return this.boundPort
    }

    start(): Promise<void> {
        if (this.server) {
            return Promise.resolve()
        }
        this.server = createServer((req, res) => {
            void this.handle(req, res)
        })
        return new Promise((resolve, reject) => {
            this.server!.once('error', reject)
            this.server!.listen(this.port, this.host, () => {
                const address = this.server!.address()
                this.boundPort =
                    address && typeof address === 'object' ? address.port : this.port
                resolve()
            })
        })
    }

    close(): void {
        this.server?.close()
        this.server = undefined
    }

    private async handle(req: IncomingMessage, res: ServerResponse): Promise<void> {
        try {
            await this.route(req, res)
        } catch (error) {
            if (error instanceof ContractUnauthorizedError) {
                json(res, 401, { error: error.message || 'unauthorized', code: 'unauthorized' })
                return
            }
            if (error instanceof ContractNotFoundError) {
                json(res, 404, { error: error.message || 'not found', code: 'not_found' })
                return
            }
            if (error instanceof ContractBusyError) {
                json(res, 409, {
                    error: error.message || 'session is busy',
                    code: 'busy',
                    hint: error.hint,
                })
                return
            }
            if (error instanceof ContractUnsupportedError) {
                json(res, 501, {
                    error: error.message || 'data plane does not support this operation',
                    code: 'unsupported',
                })
                return
            }
            const message = error instanceof Error ? error.message : 'internal error'
            json(res, 500, { error: message || 'internal error', code: 'failed' })
        }
    }

    private async route(req: IncomingMessage, res: ServerResponse): Promise<void> {
        const method = req.method ?? 'GET'
        const host = req.headers.host ?? 'localhost'
        const url = new URL(req.url ?? '/', `http://${host}`)
        const parts = url.pathname.split('/').filter(Boolean)
        if (parts[0] !== 'agentscope') {
            json(res, 404, { error: 'not found', code: 'not_found' })
            return
        }

        if (isWriteMethod(method) && !this.tokenOk(req)) {
            throw new ContractUnauthorizedError('invalid or missing X-Builder-Internal-Token')
        }

        if (parts.length === 2 && method === 'GET') {
            switch (parts[1]) {
                case 'info':
                    json(res, 200, this.provider.info())
                    return
                case 'health':
                    json(res, 200, { status: 'ok' })
                    return
                case 'sessions': {
                    const sessions = this.provider.sessions() ?? []
                    const truncated =
                        Boolean(this.provider.sessionsTruncated?.()) ||
                        sessions.length > MAX_SESSIONS_PROBE_PAGE
                    const page = sessions.slice(0, MAX_SESSIONS_PROBE_PAGE)
                    json(res, 200, { sessions: page, truncated, hasMore: truncated })
                    return
                }
                case 'subagents':
                    json(res, 200, { subagents: this.provider.subagents() ?? [] })
                    return
                case 'workspaces':
                    json(res, 200, { workspaces: this.provider.workspaces() ?? [] })
                    return
                default:
                    break
            }
        }

        if (parts.length === 4 && parts[1] === 'sessions') {
            const sessionId = decodeURIComponent(parts[2] ?? '')
            const action = parts[3]
            if (method === 'GET') {
                switch (action) {
                    case 'state':
                        json(res, 200, this.provider.sessionState(sessionId))
                        return
                    case 'context':
                        json(res, 200, this.provider.context(sessionId))
                        return
                    case 'messages': {
                        const offset = queryInt(url, 'offset', 0)
                        const limit = queryInt(url, 'limit', DEFAULT_MESSAGE_LIMIT)
                        json(res, 200, this.provider.messages(sessionId, offset, limit))
                        return
                    }
                    case 'tasks':
                        json(res, 200, this.provider.tasks(sessionId))
                        return
                    case 'subagent-tasks':
                        json(res, 200, await this.requireSubagentTasks(sessionId))
                        return
                    case 'export-transcript':
                        json(res, 200, this.requireExport(sessionId))
                        return
                    default:
                        break
                }
            } else if (method === 'POST') {
                if (action === 'compress') {
                    await this.provider.compress(sessionId)
                    json(res, 200, commandAccepted(req, sessionId, this.provider))
                    return
                }
                if (action === 'terminate') {
                    await this.provider.terminate(sessionId)
                    json(res, 200, commandAccepted(req, sessionId, this.provider))
                    return
                }
                if (action === 'abort') {
                    await this.provider.abort(sessionId)
                    json(res, 200, commandAccepted(req, sessionId, this.provider))
                    return
                }
                if (action === 'plan-mode') {
                    const body = await readBody(req)
                    await this.requirePlanMode(sessionId, body)
                    json(res, 200, commandAccepted(req, sessionId, this.provider))
                    return
                }
                if (action === 'messages') {
                    const body = await readBody(req)
                    await this.requirePostMessage(sessionId, body)
                    json(res, 202, commandAccepted(req, sessionId, this.provider))
                    return
                }
            } else if (method === 'DELETE' && action === 'subagent-tasks') {
                json(res, 400, { error: 'taskId path segment required', code: 'invalid_argument' })
                return
            }
        }

        if (
            parts.length === 5 &&
            parts[1] === 'sessions' &&
            parts[3] === 'subagent-tasks' &&
            method === 'DELETE'
        ) {
            const sessionId = decodeURIComponent(parts[2] ?? '')
            const taskId = decodeURIComponent(parts[4] ?? '')
            await this.requireCancelSubagent(sessionId, taskId)
            json(res, 200, commandAccepted(req, sessionId, this.provider))
            return
        }

        json(res, 404, { error: 'not found', code: 'not_found' })
    }

    private tokenOk(req: IncomingMessage): boolean {
        if (!this.internalToken) {
            return true
        }
        const header = headerValue(req, INTERNAL_TOKEN_HEADER)
        return header === this.internalToken
    }

    private async requireSubagentTasks(sessionId: string): Promise<Record<string, unknown>> {
        if (!this.provider.subagentTasks) {
            throw new ContractUnsupportedError('subagent-task-query is not supported')
        }
        return this.provider.subagentTasks(sessionId)
    }

    private requireExport(sessionId: string): Record<string, unknown> {
        if (!this.provider.exportTranscript) {
            throw new ContractUnsupportedError('export-transcript is not supported')
        }
        return this.provider.exportTranscript(sessionId)
    }

    private async requirePlanMode(sessionId: string, body: Buffer): Promise<void> {
        if (!this.provider.planMode) {
            throw new ContractUnsupportedError('plan-mode is not supported')
        }
        await this.provider.planMode(sessionId, body)
    }

    private async requirePostMessage(sessionId: string, body: Buffer): Promise<void> {
        if (!this.provider.postMessage) {
            throw new ContractUnsupportedError('inbound messages are not supported')
        }
        await this.provider.postMessage(sessionId, body)
    }

    private async requireCancelSubagent(sessionId: string, taskId: string): Promise<void> {
        if (!this.provider.cancelSubagentTask) {
            throw new ContractUnsupportedError('subagent-task-command is not supported')
        }
        await this.provider.cancelSubagentTask(sessionId, taskId)
    }
}

function isWriteMethod(method: string): boolean {
    return method === 'POST' || method === 'PUT' || method === 'PATCH' || method === 'DELETE'
}

function headerValue(req: IncomingMessage, name: string): string {
    const raw = req.headers[name] ?? req.headers[name.toLowerCase()]
    if (Array.isArray(raw)) {
        return raw[0] ?? ''
    }
    return typeof raw === 'string' ? raw : ''
}

function commandAccepted(
    req: IncomingMessage,
    sessionId: string,
    provider: ContractProvider,
): Record<string, unknown> {
    const header = req.headers['x-command-id']
    const commandId =
        typeof header === 'string' && header.trim() !== ''
            ? header
            : `cmd-${randomUUID().replaceAll('-', '').slice(0, 8)}`
    return {
        accepted: true,
        commandId,
        phase: provider.sessionPhase(sessionId),
        result: {},
    }
}

function queryInt(url: URL, key: string, fallback: number): number {
    const raw = url.searchParams.get(key)
    if (raw == null) {
        return fallback
    }
    const value = Number.parseInt(raw, 10)
    return Number.isFinite(value) && value >= 0 ? value : fallback
}

function json(res: ServerResponse, status: number, body: unknown): void {
    const payload = Buffer.from(JSON.stringify(body))
    res.writeHead(status, {
        'Content-Type': 'application/json',
        'Content-Length': String(payload.length),
    })
    res.end(payload)
}

function readBody(req: IncomingMessage): Promise<Buffer> {
    return new Promise((resolve, reject) => {
        const chunks: Buffer[] = []
        req.on('data', (chunk: Buffer | string) => {
            chunks.push(typeof chunk === 'string' ? Buffer.from(chunk) : chunk)
        })
        req.on('end', () => resolve(Buffer.concat(chunks)))
        req.on('error', reject)
    })
}
