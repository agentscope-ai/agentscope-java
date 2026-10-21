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

import { ControlPlaneHttpClient } from './control-plane.js'
import type { Logger } from './logger.js'

export interface RegistrationOptions {
    tenant: string
    controlHttp: string
    internalToken: string
    agentName: string
    namespace: string
    instanceId: string
    baseUrl: string
    runtime: string
    framework: string
    contractLevel: number
    capabilities: string[]
    heartbeatIntervalMs: number
    log: Logger
    client?: ControlPlaneHttpClient
}

/**
 * POST /api/v1/dataplanes/register plus periodic heartbeats.
 * Failures are swallowed and retried — registration must never disturb the agent.
 */
export class HttpSelfRegistration {
    private readonly http: ControlPlaneHttpClient
    private registered = false
    private registeredInstanceId: string | undefined
    private generation: number | undefined
    private timer: ReturnType<typeof setInterval> | undefined

    constructor(private readonly options: RegistrationOptions) {
        this.http =
            options.client ??
            new ControlPlaneHttpClient(options.controlHttp, options.internalToken)
    }

    start(): void {
        if (this.timer) {
            return
        }
        void this.tryRegister()
        this.timer = setInterval(() => {
            void this.heartbeatSafe()
        }, this.options.heartbeatIntervalMs)
        this.timer.unref?.()
    }

    async close(): Promise<void> {
        if (this.timer) {
            clearInterval(this.timer)
            this.timer = undefined
        }
        if (!this.registered) {
            return
        }
        try {
            const instanceId = this.requireRegisteredInstanceId()
            await this.http.send('DELETE', `/api/v1/dataplanes/${instanceId}`, {
                generation: this.generation,
            })
            this.options.log.info(`unregistered instance ${instanceId}`)
        } catch (error) {
            this.options.log.debug('unregister failed', error)
        } finally {
            this.registered = false
            this.registeredInstanceId = undefined
            this.generation = undefined
        }
    }

    private async heartbeatSafe(): Promise<void> {
        try {
            if (!this.registered) {
                await this.tryRegister()
                return
            }
            const instanceId = this.requireRegisteredInstanceId()
            const response = await this.http.send(
                'POST',
                `/api/v1/dataplanes/${instanceId}/heartbeat`,
                { generation: this.generation },
            )
            if (response.status === 404) {
                this.clearRegistration()
                await this.tryRegister()
            }
        } catch (error) {
            this.options.log.debug('heartbeat failed; will re-register', error)
            this.clearRegistration()
            try {
                await this.tryRegister()
            } catch {
                // swallowed
            }
        }
    }

    private async tryRegister(): Promise<void> {
        const body = {
            tenant: this.options.tenant,
            agentName: this.options.agentName,
            namespace: this.options.namespace,
            instanceId: this.options.instanceId,
            baseUrl: this.options.baseUrl.replace(/\/$/, ''),
            runtime: this.options.runtime,
            framework: this.options.framework,
            contractLevel: this.options.contractLevel,
            capabilities: this.options.capabilities,
            source: 'self-register',
        }
        try {
            const response = await this.http.send('POST', '/api/v1/dataplanes/register', body)
            if (response.status >= 200 && response.status < 300) {
                const registration = parseRegistrationResponse(response.json)
                this.registered = true
                this.registeredInstanceId = registration.instanceId
                this.generation = registration.generation
                this.options.log.info(
                    `registered ${registration.instanceId} (key=${this.options.instanceId}) at ${body.baseUrl} with ${this.options.controlHttp}`,
                )
            } else {
                this.clearRegistration()
                this.options.log.warn(`register returned HTTP ${response.status}`)
            }
        } catch (error) {
            this.clearRegistration()
            this.options.log.warn(
                `register failed (will retry): ${error instanceof Error ? error.message : String(error)}`,
            )
        }
    }

    private requireRegisteredInstanceId(): string {
        if (!this.registeredInstanceId) {
            throw new Error('control plane registration identity is missing')
        }
        return this.registeredInstanceId
    }

    private clearRegistration(): void {
        this.registered = false
        this.registeredInstanceId = undefined
        this.generation = undefined
    }
}

function parseRegistrationResponse(value: unknown): { instanceId: string; generation: number } {
    if (!value || typeof value !== 'object') {
        throw new Error('registration response must be a JSON object')
    }
    const instanceId = (value as Record<string, unknown>).instanceId
    const generation = (value as Record<string, unknown>).generation
    if (typeof instanceId !== 'string' || instanceId.length === 0) {
        throw new Error('registration response is missing instanceId')
    }
    if (typeof generation !== 'number' || !Number.isSafeInteger(generation) || generation < 1) {
        throw new Error('registration response is missing generation')
    }
    return { instanceId, generation }
}
