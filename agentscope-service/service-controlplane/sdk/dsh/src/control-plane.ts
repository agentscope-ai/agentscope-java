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

const INTERNAL_TOKEN_HEADER = 'X-Builder-Internal-Token'
const DEFAULT_TIMEOUT_MS = 10_000

export interface JsonResponse {
    status: number
    body: string
    json: unknown
}

function trimSlash(url: string): string {
    return url.replace(/\/$/, '')
}

/**
 * Control-plane JSON client. Sends {@code X-Builder-Internal-Token} on every call.
 */
export class ControlPlaneHttpClient {
    readonly baseUrl: string

    constructor(
        baseUrl: string,
        private readonly internalToken: string,
        private readonly timeoutMs = DEFAULT_TIMEOUT_MS,
        private readonly fetchImpl: typeof fetch = fetch,
    ) {
        this.baseUrl = trimSlash(baseUrl)
    }

    async send(method: string, path: string, jsonBody?: unknown, requestHeaders: Record<string, string> = {}): Promise<JsonResponse> {
        const headers: Record<string, string> = {
            Accept: 'application/json',
        }
        if (this.internalToken) {
            headers[INTERNAL_TOKEN_HEADER] = this.internalToken
        }
		Object.assign(headers, requestHeaders)
        let body: string | undefined
        if (jsonBody !== undefined && jsonBody !== null) {
            headers['Content-Type'] = 'application/json'
            body = JSON.stringify(jsonBody)
        }
        const controller = new AbortController()
        const timer = setTimeout(() => controller.abort(), this.timeoutMs)
        try {
            const response = await this.fetchImpl(this.baseUrl + path, {
                method,
                headers,
                body,
                signal: controller.signal,
            })
            const text = await response.text()
            let json: unknown = null
            if (text) {
                try {
                    json = JSON.parse(text)
                } catch {
                    json = text
                }
            }
            return { status: response.status, body: text, json }
        } finally {
            clearTimeout(timer)
        }
    }

    async sendBody(method: string, path: string, body: BodyInit | undefined, requestHeaders: Record<string, string> = {}): Promise<JsonResponse> {
        const headers: Record<string, string> = { Accept: 'application/json', ...requestHeaders }
        if (this.internalToken) headers[INTERNAL_TOKEN_HEADER] = this.internalToken
        const controller = new AbortController()
        const timer = setTimeout(() => controller.abort(), this.timeoutMs)
        try {
            const response = await this.fetchImpl(this.baseUrl + path, { method, headers, body, signal: controller.signal })
            const text = await response.text()
            let json: unknown = null
            if (text) { try { json = JSON.parse(text) } catch { json = text } }
            return { status: response.status, body: text, json }
        } finally { clearTimeout(timer) }
    }

    async receiveBytes(method: string, path: string, requestHeaders: Record<string, string> = {}): Promise<{status:number;body:Uint8Array;headers:Headers}> {
        const headers: Record<string, string> = { ...requestHeaders }
        if (this.internalToken) headers[INTERNAL_TOKEN_HEADER] = this.internalToken
        const response = await this.fetchImpl(this.baseUrl + path, { method, headers })
        return { status: response.status, body: new Uint8Array(await response.arrayBuffer()), headers: response.headers }
    }
}

export function encodePath(value: string): string {
    return encodeURIComponent(value)
}
