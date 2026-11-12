import { afterEach, expect, it, vi } from 'vitest';
import { createManagedSession, deleteManagedSession, getManagedLifecycleReceipt, listManagedSessions, managedLifecyclePending, normalizeManagedSession, updateManagedSession, type CreateManagedSessionRequest, type ManagedSessionWire } from './managedSessions';
import { answerAgentActions, cancelAgentTurn, deleteAgentArtifact, downloadAgentArtifact, injectAgentContext, publishAgentArtifact, steerAgentTurn, submitAgentTurn, uploadAgentFile } from './agentSessions';

vi.mock('./auth', () => ({ getToken: () => null }));
afterEach(() => vi.unstubAllGlobals());
const resource: ManagedSessionWire = { id: 's', managed_agents: { runtime_kind: 'managed', lifecycle: 'active' }, agent: { id: 'a', version: 2 }, environment_id: 'e', status: 'running', created_at: '2026-10-14T00:00:00Z', updated_at: '2026-10-14T00:01:00Z', metadata: { purpose: 'test' } };

it('maps pinned Session definitions and uses the canonical create and metadata paths', async () => {
  const send = vi.fn(async (_url: string, _init?: RequestInit) => { void _url; void _init; return new Response(JSON.stringify(resource)); });
  vi.stubGlobal('fetch', send);
  const created = await createManagedSession({ agent: { id: 'a', version: 2 }, environmentId: 'e' });
  expect(created).toMatchObject({ status: 'active', runtimeStatus: 'running', agentId: 'a', agentVersion: 2 });
  expect(send.mock.calls[0][0]).toBe('/v1/sessions');
  expect(JSON.parse(String(send.mock.calls[0][1]?.body))).toEqual({ agent: { type: 'agent', id: 'a', version: 2 }, environment_id: 'e' });
  await updateManagedSession('s', { title: 'Named', metadata: { purpose: 'review' } });
  expect(send.mock.calls[1][1]?.method).toBe('POST');
  expect(send.mock.calls[1][0]).toBe('/v1/sessions/s');
});

it('rejects unsupported creation fields before sending and rejects old resource envelopes', async () => {
  const send = vi.fn(); vi.stubGlobal('fetch', send);
  await expect(createManagedSession({ agent: 'a', resources: [] } as CreateManagedSessionRequest)).rejects.toThrow('unsupported_capability');
  expect(send).not.toHaveBeenCalled();
  const { managed_agents: _extension, ...standard } = resource;
  void _extension;
  expect(() => normalizeManagedSession({ ...standard, runtime_kind: 'managed', lifecycle: 'active' } as unknown as ManagedSessionWire)).toThrow('Unsupported Managed Session');
});

it('pages Session resources with opaque cursors and lifecycle filters', async () => {
  const send = vi.fn(async (_url: string, _init?: RequestInit) => { void _url; void _init; return new Response(JSON.stringify({ data: [resource], next_cursor: send.mock.calls.length === 1 ? 'opaque+/=' : null })); });
  vi.stubGlobal('fetch', send);
  expect(await listManagedSessions('a', 'all')).toHaveLength(2);
  const query = new URL(String(send.mock.calls[1][0]), 'http://fixture').searchParams;
  expect(query.get('after')).toBe('opaque+/=');
  expect(query.get('lifecycle')).toBe('all');
  expect(() => normalizeManagedSession({ id: 'old' } as ManagedSessionWire)).toThrow('Unsupported Managed Session');
});

it('keeps deletion acceptance distinct from confirmed cleanup and reads scoped receipts', async () => {
  const deletion = { id: 's', type: 'session_deleted', lifecycle_command_id: 'command', cleanup_status: 'stopping' };
  const send = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify(deletion)))
    .mockResolvedValueOnce(new Response(JSON.stringify({ id: 'command', session_id: 's', lifecycle: 'deleted', status: 'completed', revision: 2 })));
  vi.stubGlobal('fetch', send);
  expect(await deleteManagedSession('s')).toEqual(deletion);
  expect(await getManagedLifecycleReceipt('s', 'command')).toMatchObject({ status: 'completed' });
  expect(send.mock.calls[1][0]).toBe('/v1/sessions/s/lifecycle/command');
  const archived = normalizeManagedSession({ ...resource, managed_agents: { ...resource.managed_agents, lifecycle: 'archived', lifecycle_command_id: 'command', cleanup_status: 'stopping' } });
  expect(archived.lifecycleCommandId).toBe('command');
  expect(managedLifecyclePending(archived)).toBe(true);
  expect(managedLifecyclePending({ cleanupStatus: 'completed' })).toBe(false);
});

it('submits all supported controls through explicit Session events', async () => {
  const send = vi.fn(async (_url: string, init?: RequestInit) => {
    const wire = JSON.parse(String(init?.body)).events[0];
    return new Response(JSON.stringify({ data: [{ id: 'event', managed_agents: { ...wire.managed_agents, turn_id: 't', receipt: { id: 't', status: 'accepted' } } }] }));
  }); vi.stubGlobal('fetch', send);
  expect(await submitAgentTurn('s', 'hello', 'turn-key')).toMatchObject({ id: 't', sessionId: 's', status: 'accepted' });
  await steerAgentTurn('s', 't', { message: 'redirect' }, 'steer');
  await injectAgentContext('s', 't', { message: 'context' }, 'inject');
  await cancelAgentTurn('s', 't');
  for (const call of send.mock.calls) expect(call[0]).toBe('/v1/sessions/s/events');
  expect(JSON.parse(String(send.mock.calls[2][1]?.body)).events[0].managed_agents).toEqual({ intent: 'inject', turn_id: 't' });
  expect(JSON.parse(String(send.mock.calls[3][1]?.body)).events[0]).toEqual({ type: 'user.interrupt', managed_agents: { intent: 'cancel', turn_id: 't' } });
});

it('keeps unsupported tool replies outside the first protocol release', async () => {
  const send = vi.fn(); vi.stubGlobal('fetch', send);
  await expect(answerAgentActions('s', 't', [{ request_id: 'first', allow: true }], 'batch')).rejects.toThrow('unsupported_capability');
  expect(send).not.toHaveBeenCalled();
});

it('uploads file content and publishes immutable artifact references through the Managed routes', async () => {
  const send = vi.fn().mockResolvedValueOnce(new Response(JSON.stringify({ file_id: 'file-id' })))
    .mockResolvedValueOnce(new Response(JSON.stringify({ artifact_id: 'artifact-id', file_id: 'file-id' })))
    .mockResolvedValueOnce(new Response('original-content')).mockResolvedValueOnce(new Response(null, { status: 204 }));
  vi.stubGlobal('fetch', send);
  const file = new File(['original-content'], 'report.txt', { type: 'text/plain' });
  expect(await uploadAgentFile('s', file, 'file-key')).toMatchObject({ file_id: 'file-id' });
  expect(send.mock.calls[0][0]).toBe('/v1/sessions/s/files');
  expect(send.mock.calls[0][1]?.body).toBe(file);
  await publishAgentArtifact('s', 'file-id', 'publish-key');
  expect(send.mock.calls[1][0]).toBe('/v1/sessions/s/artifacts');
  expect(JSON.parse(String(send.mock.calls[1][1]?.body))).toEqual({ file_id: 'file-id' });
  expect(await (await downloadAgentArtifact('s', 'artifact-id')).text()).toBe('original-content');
  await deleteAgentArtifact('s', 'artifact-id');
  expect(send.mock.calls[3][1]?.method).toBe('DELETE');
});
