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
