import { beforeAll, describe, expect, it, vi } from 'vitest';

beforeAll(() => {
  vi.stubGlobal('localStorage', {
    getItem: () => null,
    setItem: () => {},
    removeItem: () => {},
  });
});
import { createAgent, resolveAgentKey, slugifyAgentKey } from './agents';

describe('slugifyAgentKey', () => {
  it('keeps existing ascii keys', () => {
    expect(slugifyAgentKey('presales-leader')).toBe('presales-leader');
    expect(slugifyAgentKey('agent_1')).toBe('agent_1');
  });

  it('slugifies mixed text', () => {
    expect(slugifyAgentKey('My Agent!')).toBe('my-agent');
    expect(slugifyAgentKey('  Demo Agent  ')).toBe('demo-agent');
  });

  it('returns an empty string for a fully non-ascii name', () => {
    expect(slugifyAgentKey('中文名字')).toBe('');
  });
});

describe('resolveAgentKey', () => {
  it('prefers an explicit key', () => {
    expect(resolveAgentKey('中文名字', 'sales-lead')).toBe('sales-lead');
  });

  it('slugs an ascii name with a short suffix', () => {
    const key = resolveAgentKey('My Agent');
    expect(key).toMatch(/^my-agent-[a-f0-9]{8}$/);
  });

  it('falls back to a readable agent-* key for a fully non-ascii name', () => {
    const key = resolveAgentKey('中文名字');
    expect(key).toMatch(/^agent-[a-f0-9]{8}$/);
  });
});

describe('createAgent', () => {
  it('sends a readable fallback agent key for a fully non-ascii name', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ agent: { id: 'agent-1' }, definition: { name: 'x' } }),
    });
    vi.stubGlobal('fetch', fetchMock);
    await createAgent({ name: '中文名字', tenant: 'default', namespace: 'default' } as never);
    const body = JSON.parse((fetchMock.mock.calls[0][1] as RequestInit).body as string);
    expect(body.agentKey).toMatch(/^agent-[a-f0-9]{8}$/);
    expect(body.displayName).toBe('中文名字');
    vi.unstubAllGlobals();
  });
});
