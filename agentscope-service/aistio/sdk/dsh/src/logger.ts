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
