/**
 * Minimal DSH-compatible tool definition. Out-of-tree: do not import
 * `@deepseek-ai/dsh-tool` — `register()` accepts this shape.
 */
export function defineTool(options: {
    name: string
    description: string
    parameters: Record<string, unknown>
    execute: (args: unknown, exec?: unknown) => Promise<unknown> | unknown
    timeoutMs?: number
}): Record<string, unknown> {
    return {
        name: options.name,
        description: options.description,
        parameters: options.parameters,
        timeoutMs: options.timeoutMs ?? 30_000,
        output: {
            schema: { type: 'string' },
            render(_args: unknown, value: unknown) {
                return [{ type: 'text', text: String(value ?? '') }]
            },
        },
        execute: async (args: unknown, exec?: unknown) => {
            const result = await options.execute(args, exec)
            return typeof result === 'string' ? result : JSON.stringify(result)
        },
    }
}
