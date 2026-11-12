import { test } from "node:test";
import assert from "node:assert/strict";
import { TextPreview } from "../dist/index.js";

const tick = () => new Promise((resolve) => setTimeout(resolve, 5));
async function until(predicate) {
  for (let count = 0; !predicate(); count++) {
    assert.ok(count < 100, "preview did not settle");
    await tick();
  }
}
function lane(post) {
  const owner = new AbortController();
  return { owner, preview: new TextPreview({ post }, { turnId: "turn" }, "model", "message", owner.signal) };
}

test("preview batches preserve Unicode, bound escaped wire size and continue frame sequences", async () => {
  const requests = [];
  const { owner, preview } = lane(async (operation, request) => {
    if (operation.endsWith("interest")) return { interested: true };
    requests.push(request);
    return { accepted: true };
  });
  const first = "🙂你".repeat(1500) + "\u0001".repeat(9000);
  preview.accept(first);
  await until(() => requests.flatMap((r) => r.chunks).join("") === first);
  preview.accept("second");
  await preview.finish();
  let next = 0;
  for (const request of requests) {
    assert.equal(request.sequence, String(next));
    assert.ok(Buffer.byteLength(JSON.stringify(request)) < 64 * 1024);
    for (const chunk of request.chunks) assert.ok(Array.from(chunk).length <= 1024);
    next += request.chunks.length + (next === 0 ? 1 : 0);
  }
  assert.equal(requests.flatMap((r) => r.chunks).join(""), first + "second");
  assert.equal(owner.signal.aborted, false);
});

test("no subscriber means no preview payload and no retries", async () => {
  const operations = [];
  const { owner, preview } = lane(async (operation) => {
    operations.push(operation);
    return { interested: false };
  });
  preview.accept("text");
  await preview.finish();
  preview.accept("late");
  assert.deepEqual(operations, ["previews/interest"]);
  assert.equal(owner.signal.aborted, false);
});

test("preview failure or rejection stops only the preview lane without retry", async () => {
  for (const throws of [false, true]) {
    const operations = [];
    const { owner, preview } = lane(async (operation) => {
      operations.push(operation);
      if (operation.endsWith("interest")) return { interested: true };
      if (throws) throw new Error("transport failed");
      return { accepted: false };
    });
    preview.accept("text");
    await preview.finish();
    preview.accept("late");
    assert.deepEqual(operations, ["previews/interest", "previews/publish"]);
    assert.equal(owner.signal.aborted, false);
  }
});

test("hung preview is aborted within bounded finish time without cancelling execution", async () => {
  let aborted = false;
  const { owner, preview } = lane(async (operation, _request, signal) => {
    if (operation.endsWith("interest")) return { interested: true };
    return new Promise((_resolve, reject) => signal.addEventListener("abort", () => {
      aborted = true;
      reject(signal.reason);
    }, { once: true }));
  });
  preview.accept("text");
  const started = performance.now();
  await preview.finish();
  assert.ok(performance.now() - started < 700);
  assert.equal(aborted, true);
  assert.equal(owner.signal.aborted, false);
});

test("oversized queues and invalid surrogate text stop before publication", async () => {
  for (const text of ["x".repeat(65537), "\ud800"]) {
    let publications = 0;
    const { owner, preview } = lane(async (operation) => {
      if (operation.endsWith("publish")) publications++;
      return { interested: true, accepted: true };
    });
    preview.accept(text);
    await preview.finish();
    assert.equal(publications, 0);
    assert.equal(owner.signal.aborted, false);
  }
});
