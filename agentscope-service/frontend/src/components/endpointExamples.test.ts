import { expect, it } from 'vitest';
import type { Endpoint } from '@/api/agentEndpoints';
import { endpointExamples, schemaExample } from './endpointExamples';
it('uses the published jobs contract, API key, idempotency and public polling paths', () => {
  const endpoint = { slug: 'team-review', invocationMode: 'job', authPolicy: { type: 'api_key' }, inputSchema: { type: 'object', properties: { topic: { const: "review ' $(secret)" }, count: { type: 'integer', minimum: 2 } } } } as Endpoint;
  const samples = endpointExamples(endpoint, 'https://service.example');
  expect(samples.url).toBe('https://service.example/invoke/v1/endpoints/team-review/jobs');
  expect(samples.submit).toContain('X-API-Key: $ENDPOINT_TOKEN');
  expect(samples.submit).toContain('Idempotency-Key: $REQUEST_KEY');
  expect(samples.submit).toContain('"input": {');
  expect(samples.submit).toContain("review '\"'\"' $(secret)");
  expect(samples.status).toContain('/invoke/v1/jobs/INVOCATION_ID');
  expect(samples.accepted.statusUrl).toBe('/invoke/v1/jobs/INVOCATION_ID');
  expect(samples.completed.invocation.status).toBe('completed');
});
it('keeps conversation and platform authentication distinct', () => {
  const samples = endpointExamples({ slug: 'chat', invocationMode: 'conversation', authPolicy: { type: 'platform' } } as Endpoint, 'https://service.example');
  expect(samples.submit).toContain('Authorization: Bearer $ENDPOINT_TOKEN');
  expect(samples.submit).not.toContain('X-API-Key');
  expect(samples.submit).toContain('"message"');
  expect(samples.events).toContain('/conversations/CONVERSATION_ID/events?invocationId=INVOCATION_ID');
  expect(schemaExample({ type: 'object', properties: { mode: { enum: ['review', 'write'] } } })).toEqual({ mode: 'review' });
});
