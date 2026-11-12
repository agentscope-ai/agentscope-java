import { test } from "node:test";
import assert from "node:assert/strict";
import { SessionServicePiStorage } from "../dist/durable-storage.js";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";

test("native writes become visible only after the Session Service acknowledgement", async () => {
  let acknowledge, entered;
  const started = new Promise((resolve) => (entered = resolve));
  const storage = new SessionServicePiStorage(async () => {
    entered();
    await new Promise((resolve) => (acknowledge = resolve));
  });
  const commit = storage.commit(
    [{ type: "conversation", value: { id: 1 } }],
    BACKGROUND_CONTEXT,
  );
  await started;
  assert.equal(await storage.conversation(1, BACKGROUND_CONTEXT), undefined);
  acknowledge();
  await commit;
  assert.deepEqual(await storage.conversation(1, BACKGROUND_CONTEXT), {
    id: 1,
  });
});

test("failed persistence never advances local state or permits a subsequent write", async () => {
  const storage = new SessionServicePiStorage(async () => {
    throw new Error("lost receipt");
  });
  await assert.rejects(
    storage.commit(
      [{ type: "conversation", value: { id: 1 } }],
      BACKGROUND_CONTEXT,
    ),
    /lost receipt/,
  );
  assert.equal(await storage.conversation(1, BACKGROUND_CONTEXT), undefined);
  await assert.rejects(storage.mintId(), /lost receipt/);
});

test("SDK restores its own records and allocator never reuses a saved identity", async () => {
  const storage = new SessionServicePiStorage(async () => {});
  const id = await storage.mintId();
  await storage.commit(
    [{ type: "conversation", value: { id } }],
    BACKGROUND_CONTEXT,
  );
  const recovered = new SessionServicePiStorage(async () => {});
  await recovered.restore(storage.snapshot());
  assert.deepEqual(await recovered.conversation(id, BACKGROUND_CONTEXT), {
    id,
  });
  assert.equal(await recovered.mintId(), id + 1);
});

test("the real durable Harness reopens its native root from service-backed storage", async () => {
  const { Harness, createRegistry } = await import(
    "@earendil-works/pi-durable"
  );
  const { ModelRuntime } = await import("@earendil-works/pi-coding-agent");
  const storage = new SessionServicePiStorage(async () => {});
  const options = {
    models: await ModelRuntime.create(),
    registry: createRegistry(),
  };
  const first = await Harness.open(storage, options, BACKGROUND_CONTEXT);
  const root = await first.root(BACKGROUND_CONTEXT, {
    agent: { instructions: "fixture" },
  });
  const identity = root.id;
  await first.close(BACKGROUND_CONTEXT);
  const next = new SessionServicePiStorage(async () => {});
  await next.restore(storage.snapshot());
  const recovered = await Harness.open(next, options, BACKGROUND_CONTEXT);
  try {
    assert.equal((await recovered.root(BACKGROUND_CONTEXT)).id, identity);
  } finally {
    await recovered.close(BACKGROUND_CONTEXT);
  }
});

test("reused native history is immutable while exported snapshots remain isolated", async () => {
  const saved = [];
  const storage = new SessionServicePiStorage(async (snapshot) =>
    saved.push(snapshot),
  );
  await storage.commit(
    [{ type: "conversation", value: { id: 1 } }],
    BACKGROUND_CONTEXT,
  );
  await storage.mintId();
  assert.equal(saved[0].commits, saved[1].commits);
  assert.throws(() => {
    saved[0].commits[0].writes[0].value.id = 999;
  }, TypeError);
  const exported = storage.snapshot();
  exported.commits.length = 0;
  assert.equal(storage.snapshot().commits.length, 1);
});
