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
