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

import type { PluginLogger } from './types.js'

export interface Logger {
    info(message: string, extra?: unknown): void
    warn(message: string, extra?: unknown): void
    error(message: string, extra?: unknown): void
    debug(message: string, extra?: unknown): void
}

function emit(
    logger: PluginLogger | undefined,
    level: keyof Logger,
    message: string,
    extra?: unknown,
): void {
    try {
        const method = logger?.[level]
        if (typeof method === 'function') {
            method.call(logger, `[aistio] ${message}`, extra)
            return
        }
        const fallback = extra === undefined ? message : `${message} ${stringify(extra)}`
        if (level === 'error') {
            console.error(`[aistio] ${fallback}`)
        } else if (level === 'warn') {
            console.warn(`[aistio] ${fallback}`)
        } else if (level === 'debug') {
            return
        } else {
            console.info(`[aistio] ${fallback}`)
        }
    } catch {
        // Observation must never disturb the agent.
    }
}

function stringify(value: unknown): string {
    try {
        return typeof value === 'string' ? value : JSON.stringify(value)
    } catch {
        return String(value)
    }
}

export function createLogger(logger?: PluginLogger): Logger {
    return {
        info: (message, extra) => emit(logger, 'info', message, extra),
        warn: (message, extra) => emit(logger, 'warn', message, extra),
        error: (message, extra) => emit(logger, 'error', message, extra),
        debug: (message, extra) => emit(logger, 'debug', message, extra),
    }
}
