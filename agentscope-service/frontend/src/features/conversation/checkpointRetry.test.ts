import { afterEach, expect, it, vi } from 'vitest';
import { clearCheckpointRetry, readCheckpointRetry, saveCheckpointRetry } from './checkpointRetry';

afterEach(() => vi.unstubAllGlobals());
it('retains an uncertain request and ignores stale replies after a replacement request', () => {
  const values = new Map<string, string>();
  vi.stubGlobal('sessionStorage', { getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value), removeItem: (key: string) => values.delete(key) });
  const first = { checkpointId: 'checkpoint_saved', key: 'same-request' };
  saveCheckpointRetry('s', first);
  expect(readCheckpointRetry('s').pending).toEqual(first);
  const receipt = { command_id: 'command', operation_id: 'command', source_session_id: 's', command_status: 'validating', session_id: 's' };
  saveCheckpointRetry('s', { ...first, receipt }, true);
  expect(readCheckpointRetry('s').pending?.receipt).toEqual(receipt);
  clearCheckpointRetry('s', first.key);
  saveCheckpointRetry('s', { ...first, receipt }, true);
  expect(readCheckpointRetry('s').pending).toBeUndefined();
  const second = { ...first, checkpointId: 'checkpoint_new', key: 'new-request' };
  saveCheckpointRetry('s', second);
  clearCheckpointRetry('s', first.key);
  saveCheckpointRetry('s', { ...first, receipt }, true);
  expect(readCheckpointRetry('s').pending).toEqual(second);
  expect(() => saveCheckpointRetry('s', first)).toThrow();
  expect(readCheckpointRetry('s').pending).toEqual(second);
});
it('refuses corrupt or unavailable retry storage instead of starting a new untracked request', () => {
  vi.stubGlobal('sessionStorage', { getItem: () => '{bad}', setItem: vi.fn() });
  expect(readCheckpointRetry('s').error).toBeTruthy();
  expect(() => saveCheckpointRetry('s', { checkpointId: 'checkpoint_saved', key: 'key' })).toThrow();
  expect(sessionStorage.setItem).not.toHaveBeenCalled();
});
it('retains native checkpoint IDs and rejects a receipt from a different source Session', () => {
  const values = new Map<string, string>();
  vi.stubGlobal('sessionStorage', { getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => values.set(key, value), removeItem: (key: string) => values.delete(key) });
  const first = { checkpointId: 'checkpoint_' + 'a'.repeat(64), target: 'target', key: 'fork-key',
    receipt: { command_id: 'operation', operation_id: 'operation', command_status: 'restoring',
      source_session_id: 'source', session_id: 'target' } };
  saveCheckpointRetry('source', first);
  expect(readCheckpointRetry('source').pending).toEqual(first);
  const stored = [...values.keys()][0];
  values.set(stored, JSON.stringify({ ...first, receipt: { ...first.receipt, source_session_id: 'other' } }));
  expect(readCheckpointRetry('source').error).toBeTruthy();
  expect(() => saveCheckpointRetry('source', { checkpointId: 'checkpoint_new', key: 'new-key' })).toThrow();
});
