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

import { hostname } from 'node:os'
import type { PluginConfig, ResolvedConfig } from './types.js'

function env(name: string): string {
    const value = process.env[name]
    return value == null ? '' : value.trim()
}

function firstNonBlank(...values: Array<string | undefined>): string {
    for (const value of values) {
        if (value != null && value.trim() !== '') {
            return value.trim()
        }
    }
    return ''
}

function parsePort(raw: string | number | undefined, fallback: number): number {
    const value = typeof raw === 'number' ? raw : Number.parseInt(String(raw ?? ''), 10)
    return Number.isFinite(value) && value >= 0 ? value : fallback
}

function parseBool(raw: string | boolean | undefined, fallback: boolean): boolean {
    if (typeof raw === 'boolean') {
        return raw
    }
    if (raw == null || raw === '') {
        return fallback
    }
    return raw === '1' || raw.toLowerCase() === 'true' || raw.toLowerCase() === 'yes'
}

/**
 * Merge plugin config with environment defaults used by the Java BYO path.
 */
export function resolveConfig(raw: PluginConfig = {}): ResolvedConfig {
    const internalToken = firstNonBlank(
        raw.internalToken,
        env('BUILDER_INTERNAL_TOKEN'),
        env('CONTROL_PLANE_INTERNAL_TOKEN'),
    )
    const controlHttp = firstNonBlank(
        raw.controlHttp,
        env('CONTROL_PLANE_HTTP'),
        env('BUILDER_CONTROL_URL'),
        'http://localhost:8081',
    ).replace(/\/$/, '')
    return {
		tenant: firstNonBlank(raw.tenant, env('CONTROL_PLANE_TENANT'), 'default'),
        controlHttp,
        internalToken,
        agentName: firstNonBlank(raw.agentName, env('CONTROL_PLANE_AGENT_NAME'), 'deepseek-harness'),
        namespace: firstNonBlank(raw.namespace, env('CONTROL_PLANE_NAMESPACE'), 'default'),
        instanceId: firstNonBlank(
            raw.instanceId,
            env('CONTROL_PLANE_INSTANCE_ID'),
            env('HOSTNAME'),
            hostname(),
        ),
        contractHost: firstNonBlank(raw.contractHost, env('CONTROL_PLANE_CONTRACT_HOST'), '127.0.0.1'),
        contractPort: parsePort(
            raw.contractPort ?? env('CONTROL_PLANE_CONTRACT_PORT'),
            18091,
        ),
        publicBaseUrl: firstNonBlank(raw.publicBaseUrl, env('CONTROL_PLANE_PUBLIC_BASE_URL')),
        startHttp: parseBool(raw.startHttp, true),
        startHttpRegister: parseBool(
            raw.startHttpRegister,
            parseBool(env('CONTROL_PLANE_HTTP_REGISTER'), true),
        ),
        heartbeatIntervalMs: parsePort(
            raw.heartbeatIntervalMs ?? env('CONTROL_PLANE_HEARTBEAT_MS'),
            15_000,
        ),
        controlGrpc: firstNonBlank(
            raw.controlGrpc,
            env('CONTROL_PLANE_GRPC'),
            'localhost:15010',
        ),
        startGrpc: parseBool(
            raw.startGrpc,
            parseBool(env('CONTROL_PLANE_ENABLE_GRPC'), internalToken !== ''),
        ),
        transcriptDir: firstNonBlank(raw.transcriptDir, env('CONTROL_PLANE_TRANSCRIPT_DIR')),
    }
}
