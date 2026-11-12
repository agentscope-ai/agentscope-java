// Real Agent Service process fixture; only the caller's test identity is provided.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { ServiceClient } from '../control-plane/sdk/dsh/dist/java-client.js';
const [base, session, turn, artifact, digest] = process.argv.slice(2);
const client = new ServiceClient(base, { apiToken: process.env.MANAGED_AGENTS_TEST_TOKEN });
assert.equal((await client.capabilities(session)).capabilities.artifacts, true);
const snapshot = await client.snapshot(session, turn, 10);
assert.equal(snapshot.resources.turns.data[0].data.status, 'completed');
assert.deepEqual((await client.usage(session, turn)).totals, { inputTokens: 3, outputTokens: 2 });
assert.equal((await client.resources(session, 'artifacts', { turn, limit: 10 })).data.length, 1);
const content = await client.artifactContent(session, artifact, turn);
assert.equal(createHash('sha256').update(content).digest('hex'), digest);
const first = (await client.events(session, '', 1, turn)).data[0];
let observed = false;
for await (const event of client.stream(session, first.cursor, turn)) {
  assert.equal(event.schema_version, 1); assert.ok(event.cursor); assert.notEqual(event.id, first.id); observed = true; break;
}
assert.equal(observed, true);
console.log('DSH public ServiceClient passed: real Agent Service resource snapshot, capabilities, authoritative usage, artifact bytes and SSE.');
