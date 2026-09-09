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
import { afterEach, test } from 'node:test'
import { resolveConfig } from '../src/config.ts'

const saved: Record<string, string | undefined> = {}

function setEnv(name: string, value: string | undefined): void {
    if (!(name in saved)) {
        saved[name] = process.env[name]
    }
    if (value === undefined) {
        delete process.env[name]
    } else {
        process.env[name] = value
    }
}

afterEach(() => {
    for (const [name, value] of Object.entries(saved)) {
        if (value === undefined) {
            delete process.env[name]
        } else {
            process.env[name] = value
        }
        delete saved[name]
    }
})

test('resolveConfig uses plugin fields over env', () => {
    setEnv('AISTIO_AGENT_NAME', 'from-env')
    const config = resolveConfig({
        agentName: 'from-plugin',
        internalToken: 'token-token-token-token-token-32',
        controlHttp: 'http://cp:8081/',
    })
    assert.equal(config.agentName, 'from-plugin')
    assert.equal(config.controlHttp, 'http://cp:8081')
    assert.equal(config.contractPort, 18091)
    assert.equal(config.controlGrpc, 'localhost:15010')
})

test('resolveConfig allows observation-only mode when token is blank', () => {
    setEnv('BUILDER_INTERNAL_TOKEN', undefined)
    setEnv('AISTIO_INTERNAL_TOKEN', undefined)
    const config = resolveConfig({ agentName: 'dsh' })
    assert.equal(config.internalToken, '')
	assert.equal(config.startGrpc, false)
})
