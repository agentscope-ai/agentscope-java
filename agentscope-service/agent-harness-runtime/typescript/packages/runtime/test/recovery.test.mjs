import { test } from "node:test";
import assert from "node:assert/strict";
import { loadRecovery } from "../dist/recovery.js";
import { NativeSnapshots } from "../dist/snapshot.js";
import { sha256 } from "../dist/index.js";
const scope = { tenant: "t", namespace: "n", sessionId: "s" };
const claim = { scope, turnId: "turn", fence: {} };
const bundle = () => ({
  brainRevision: "5001",
  acceptedResults: [],
  toolOperations: [],
  resultApplications: [],
  inputApplications: [],
  pendingInputs: [],
  interactionUpdates: [],
  modelUpdates: [],
});

test("paged recovery retains more than 4096 boundaries and renews before reading", async () => {
  const calls = [];
  const client = {
    post: async (path, request) => {
      calls.push({ path, request });
      if (path === "executions/renew")
        return {
          serverTime: "2000-01-01T00:00:00Z",
          expiresAt: "2000-01-01T00:01:00Z",
        };
      const start = Number(request.cursor?.afterRevision ?? 0),
        end = Math.min(5001, start + request.pageSize);
      const page = bundle();
      page.modelUpdates = Array.from({ length: end - start }, (_, i) => ({
        modelCallId: String(start + i + 1),
      }));
      return {
        snapshotId: "snapshot",
        bundle: page,
        ...(end < 5001
          ? {
              nextCursor: {
                afterRevision: String(end),
                throughRevision: "5001",
              },
            }
          : {}),
      };
    },
  };
  const actual = await loadRecovery(client, claim, 16);
  assert.equal(actual.modelUpdates.length, 5001);
  assert.equal(actual.modelUpdates[5000].modelCallId, "5001");
  assert.equal(calls[0].path, "executions/renew");
  assert.equal(calls[1].request.pageSize, 16);
});

test("recovery rejects changed heads and nonadvancing continuations", async () => {
  for (const changed of [true, false]) {
    let read = 0;
    const client = {
      post: async (path) =>
        path === "executions/renew"
          ? {
              serverTime: "2000-01-01T00:00:00Z",
              expiresAt: "2000-01-01T00:01:00Z",
            }
          : {
              snapshotId: changed && ++read > 1 ? "new" : "snapshot",
              bundle: bundle(),
              nextCursor: { afterRevision: "1", throughRevision: "5001" },
            },
    };
    await assert.rejects(
      loadRecovery(client, claim, 16),
      changed ? /brain_revision_conflict/ : /continuation/,
    );
  }
});

test("legacy recovery remains a single read and object-backed pages are supported", async () => {
  let calls = 0;
  const actual = await loadRecovery(
    {
      post: async (path) => {
        calls++;
        assert.equal(path, "recovery/read");
        return { bundleRef: {} };
      },
      read: async () => Buffer.from(JSON.stringify(bundle())),
    },
    claim,
  );
  assert.equal(actual.brainRevision, "5001");
  assert.equal(calls, 1);
});

function objects() {
  const data = new Map(),
    uploads = [];
  return {
    data,
    uploads,
    client: {
      put: async (scope, bytes, formatVersion) => {
        const hash = sha256(bytes);
        data.set(hash, Buffer.from(bytes));
        uploads.push(bytes.length);
        return {
          scope,
          backendId: "test",
          key: hash,
          sha256: hash,
          size: String(bytes.length),
          mediaType: "application/json",
          formatVersion,
        };
      },
      read: async (ref) => {
        const bytes = data.get(ref.key);
        if (!bytes || sha256(bytes) !== ref.sha256)
          throw new Error("integrity");
        return bytes;
      },
    },
  };
}
for (const field of ["entries", "commits"])
  test(`segmented ${field} checkpoints reuse immutable prefixes across restart`, async () => {
    const { client, uploads } = objects();
    const writer = new NativeSnapshots(
      client,
      scope,
      "native-v1",
      new AbortController().signal,
    );
    const value = {
      version: 1,
      allocatedThrough: 400,
      [field]: Array.from({ length: 400 }, (_, i) => ({
        id: i,
        content: "x".repeat(8192),
      })),
    };
    const first = await writer.write(value, field),
      initialBytes = uploads.reduce((a, b) => a + b, 0);
    assert.ok(first.journalRefs.length > 1);
    const reader = new NativeSnapshots(
      client,
      scope,
      "native-v1",
      new AbortController().signal,
    );
    assert.deepEqual(await reader.read(first), value);
    uploads.length = 0;
    value[field].push({ id: 400, content: "next" });
    value.allocatedThrough++;
    const next = await reader.write(value, field);
    assert.ok(uploads.reduce((a, b) => a + b, 0) < initialBytes / 4);
    assert.deepEqual(
      next.journalRefs.slice(0, -1),
      first.journalRefs.slice(0, -1),
    );
    assert.deepEqual(await writer.read(next), value);
  });

test("snapshots support legacy JSON, reject missing/tampered/cross-scope segments and never truncate", async () => {
  const { client, data } = objects(),
    codec = new NativeSnapshots(
      client,
      scope,
      "v1",
      new AbortController().signal,
    );
  const legacy = { version: 1, entries: [{ id: 1 }] };
  assert.deepEqual(
    await codec.read(await codec.write(legacy, "entries")),
    legacy,
  );
  const state = await codec.write(
    { version: 1, entries: Array.from({ length: 128 }, (_, id) => ({ id })) },
    "entries",
  );
  await assert.rejects(codec.read({ ...state, journalRefs: [] }), /manifest/);
  const cross = structuredClone(state);
  cross.journalRefs[0].scope.sessionId = "other";
  await assert.rejects(codec.read(cross), /Cross-scope/);
  data.set(state.journalRefs[0].key, Buffer.from("[]"));
  await assert.rejects(codec.read(state), /integrity/);
});
