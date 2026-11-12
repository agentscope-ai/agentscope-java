import { randomUUID } from "node:crypto";
import {
  canonicalHash,
  ServiceError,
  type Fence,
  type ObjectRef,
  type Recovery,
  type Scope,
  type SessionClient,
} from "./index.js";
interface Cursor {
  afterRevision: string;
  throughRevision: string;
}
interface Envelope {
  snapshotId?: string;
  bundle?: Recovery;
  bundleRef?: ObjectRef;
  nextCursor?: Cursor;
}
const fields = [
  "acceptedResults",
  "toolOperations",
  "resultApplications",
  "inputApplications",
  "interactionUpdates",
  "modelUpdates",
] as const;

/** Opt-in pages pin native state/platform facts to a head; tool/input observations may advance. */
export async function loadRecovery(
  client: SessionClient,
  claim: { scope: Scope; turnId: string; fence: Fence },
  pageSize?: number,
  signal?: AbortSignal,
): Promise<Recovery> {
  const controller = new AbortController();
  const workSignal = signal
    ? AbortSignal.any([signal, controller.signal])
    : controller.signal;
  const identity = {
    protocolVersion: "0.1.0",
    scope: claim.scope,
    turnId: claim.turnId,
    fence: claim.fence,
  };
  let timer: NodeJS.Timeout | undefined,
    renewing = false;
  const renew = async () => {
    if (renewing) return;
    renewing = true;
    try {
      const receipt = await client.post<{
        serverTime: string;
        expiresAt: string;
      }>(
        "executions/renew",
        { ...identity, renewalId: randomUUID() },
        workSignal,
      );
      if (
        !(Date.parse(receipt.expiresAt) - Date.parse(receipt.serverTime) > 1000)
      )
        throw new Error("Invalid recovery lease receipt");
    } finally {
      renewing = false;
    }
  };
  try {
    if (pageSize !== undefined) {
      if (!Number.isInteger(pageSize) || pageSize < 1 || pageSize > 16)
        throw new Error("Invalid recovery page size");
      await renew();
      timer = setInterval(() => {
        void renew().catch((error) => controller.abort(error));
      }, 10000);
      timer.unref();
    }
    let merged: Recovery | undefined,
      cursor: Cursor | undefined,
      snapshotId: string | undefined;
    let bytes = 0,
      records = 0;
    do {
      workSignal.throwIfAborted();
      const envelope: Envelope = await client.post(
        "recovery/read",
        {
          ...identity,
          ...(pageSize === undefined ? {} : { pageSize }),
          ...(cursor ? { cursor } : {}),
        },
        workSignal,
      );
      if (!envelope.bundle && !envelope.bundleRef)
        throw new Error("Missing recovery bundle");
      const page =
        envelope.bundle ??
        (JSON.parse(
          Buffer.from(
            await client.read(envelope.bundleRef!, workSignal),
          ).toString("utf8"),
        ) as Recovery);
      workSignal.throwIfAborted();
      bytes += Buffer.byteLength(JSON.stringify(page));
      records += fields.reduce((sum, field) => sum + page[field].length, 0);
      if (bytes > 512 * 1024 * 1024 || records > 1_000_000)
        throw new ServiceError(413, "recovery_limit_exceeded");
      if (merged) {
        if (
          snapshotId !== envelope.snapshotId ||
          merged.brainRevision !== page.brainRevision ||
          canonicalHash(merged.nativeState ?? null) !==
            canonicalHash(page.nativeState ?? null)
        )
          throw new ServiceError(409, "brain_revision_conflict");
        for (const field of fields)
          (merged[field] as unknown[]).push(...page[field]);
        merged.pendingInputs = page.pendingInputs;
        if (page.terminal) merged.terminal = page.terminal;
      } else {
        merged = page;
        snapshotId = envelope.snapshotId;
      }
      if (envelope.nextCursor) {
        const next = envelope.nextCursor;
        if (
          pageSize === undefined ||
          !snapshotId ||
          !/^(0|[1-9][0-9]*)$/.test(next.afterRevision) ||
          next.throughRevision !== page.brainRevision ||
          BigInt(next.afterRevision) <= BigInt(cursor?.afterRevision ?? 0) ||
          BigInt(next.afterRevision) > BigInt(next.throughRevision)
        )
          throw new Error("Invalid recovery continuation");
      }
      cursor = envelope.nextCursor;
    } while (cursor);
    return merged;
  } finally {
    if (timer) clearInterval(timer);
    controller.abort(new Error("Recovery reader closed"));
  }
}
