import type { Endpoint } from '@/api/agentEndpoints';

// Examples are editable starting points; the published schema is authoritative.
export function schemaExample(schema: unknown, depth = 0): unknown {
  if (!schema || typeof schema !== 'object' || depth > 8) return {};
  const s = schema as Record<string, any>;
  if (s.const !== undefined) return s.const;
  if (Array.isArray(s.examples) && s.examples.length) return s.examples[0];
  if (s.default !== undefined) return s.default;
  if (Array.isArray(s.enum) && s.enum.length) return s.enum[0];
  if (s.type === 'string') return 'Your value';
  if (s.type === 'boolean') return true;
  if (s.type === 'number' || s.type === 'integer') return s.minimum ?? 0;
  if (s.type === 'array') return [schemaExample(s.items, depth + 1)];
  if (s.type === 'null') return null;
  if (s.properties) return Object.fromEntries(Object.entries(s.properties).map(([key, value]) => [key, schemaExample(value, depth + 1)]));
  return {};
}
const quote = (text: string) => "'" + text.replace(/'/g, "'\"'\"'") + "'";
export function endpointExamples(endpoint: Endpoint, origin: string) {
  const job = endpoint.invocationMode === 'job';
  const path = `/invoke/v1/endpoints/${encodeURIComponent(endpoint.slug)}/${job ? 'jobs' : 'conversations'}`;
  const auth = endpoint.authPolicy?.type === 'platform' ? 'Authorization: Bearer $ENDPOINT_TOKEN' : 'X-API-Key: $ENDPOINT_TOKEN';
  const body = job ? { title: 'Example request', description: 'Describe the work to complete.', input: schemaExample(endpoint.inputSchema) } : { message: 'Describe the work to complete.' };
  const submit = `export BASE_URL=${quote(origin)}\nexport ENDPOINT_TOKEN='REPLACE_WITH_YOUR_CREDENTIAL'\nexport REQUEST_KEY='REPLACE_WITH_A_UNIQUE_REQUEST_ID'\n\ncurl -sS --fail-with-body -X POST "$BASE_URL${path}" \\\n  -H "${auth}" \\\n  -H 'Content-Type: application/json' \\\n  -H "Idempotency-Key: $REQUEST_KEY" \\\n  --data ${quote(JSON.stringify(body, null, 2))}`;
  const prefix = job ? '/invoke/v1/jobs/INVOCATION_ID' : '/invoke/v1/conversations/CONVERSATION_ID';
  const status = `# Use the statusUrl returned by the submission.\ncurl -sS --fail-with-body "$BASE_URL${prefix}" \\\n  -H "${auth}"`;
  const events = `# Use the eventsUrl returned by the submission.\ncurl -N --fail-with-body "$BASE_URL${prefix}/events${job ? '' : '?invocationId=INVOCATION_ID'}" \\\n  -H "${auth}" \\\n  -H 'Accept: text/event-stream'`;
  const accepted = job ? { invocationId: 'INVOCATION_ID', status: 'running', statusUrl: prefix, eventsUrl: `${prefix}/events` } : { invocationId: 'INVOCATION_ID', conversationId: 'CONVERSATION_ID', status: 'running', statusUrl: prefix, eventsUrl: `${prefix}/events?invocationId=INVOCATION_ID` };
  const completed = { invocation: { id: 'INVOCATION_ID', status: 'completed', result: endpoint.outputSchema ? schemaExample(endpoint.outputSchema) : { output: 'Result produced by the target' } } };
  return { url: `${origin}${path}`, submit, status, events, accepted, completed };
}
