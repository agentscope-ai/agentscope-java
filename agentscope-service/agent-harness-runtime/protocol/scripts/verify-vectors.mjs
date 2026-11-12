// Cross-language fixture check only; this is not a production SDK serializer.
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';

const vectors = JSON.parse(readFileSync(new URL('../examples/vectors.json', import.meta.url), 'utf8'));
const hash = value => createHash('sha256').update(value).digest();

// For the I-JSON fixture domain, ECMAScript number/string encoding and UTF-16 key order
// provide an independent check against Python's RFC 8785 library and checked-in vectors.
function canonical(value) {
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
  if (value && typeof value === 'object') {
    return `{${Object.keys(value).sort().map(key => `${JSON.stringify(key)}:${canonical(value[key])}`).join(',')}}`;
  }
  return JSON.stringify(value);
}

for (const vector of vectors.canonical) {
  const bytes = Buffer.from(canonical(vector.value), 'utf8');
  assert.equal(bytes.toString('utf8'), vector.canonical, vector.name);
  assert.equal(hash(bytes).toString('hex'), vector.sha256, vector.name);
}
for (const vector of vectors.identity) {
  const bytes = Buffer.from(vector.parts.map(value => `${value.length}:${value}`).join(''), 'utf8');
  assert.equal(bytes.toString('hex'), vector.framedUtf8Hex);
  assert.equal(hash(bytes).toString('hex'), vector.sha256);
  assert.equal(hash(bytes).toString('base64url'), vector.base64url);
}
console.log(`PASS: Node.js verified ${vectors.canonical.length} JCS and ${vectors.identity.length} identity vectors.`);
