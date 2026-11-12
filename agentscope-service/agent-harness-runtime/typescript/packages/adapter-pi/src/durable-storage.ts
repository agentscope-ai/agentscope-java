import {
  MemoryStorage,
  type Id,
  type Seq,
  type StorageWrite,
} from "@earendil-works/pi-durable";
import type { Context } from "@earendil-works/chord";

export interface DurableSnapshot {
  version: 1;
  allocatedThrough: number;
  commits: { sequence: number; writes: StorageWrite[] }[];
}
function immutable<T>(value: T): T {
  if (value && typeof value === "object" && !Object.isFrozen(value)) {
    for (const child of Object.values(value)) immutable(child);
    Object.freeze(value);
  }
  return value;
}
/** SDK reads/recovery use the native MemoryStorage implementation; its write acknowledgement is remote-durable. */
export class SessionServicePiStorage extends MemoryStorage {
  // PI reserves ID 1 for its root conversation; native allocation starts at 2.
  private snapshotState: DurableSnapshot = immutable({
    version: 1,
    allocatedThrough: 1,
    commits: [],
  });
  private lane: Promise<unknown> = Promise.resolve();
  private failed: unknown;
  constructor(
    private readonly persist: (
      snapshot: DurableSnapshot,
      writes: readonly StorageWrite[],
    ) => Promise<void>,
  ) {
    super();
  }
  async restore(snapshot: DurableSnapshot): Promise<void> {
    if (
      snapshot.version !== 1 ||
      this.snapshotState.commits.length ||
      this.snapshotState.allocatedThrough !== 1 ||
      snapshot.allocatedThrough < 1
    )
      throw new Error("Invalid durable storage restore");
    for (const commit of snapshot.commits)
      this.prepareCommit(commit.writes, commit.sequence as Seq).apply();
    this.snapshotState = immutable(structuredClone(snapshot));
  }
  snapshot(): DurableSnapshot {
    return structuredClone(this.snapshotState);
  }
  private serialize<T>(work: () => Promise<T>): Promise<T> {
    const next = this.lane.then(async () => {
      if (this.failed) throw this.failed;
      return work();
    });
    this.lane = next.catch((error) => {
      this.failed = error;
    });
    return next;
  }
  override mintId<I extends Id<string>>(): Promise<I> {
    return this.serialize(async () => {
      const candidate = immutable({
        ...this.snapshotState,
        allocatedThrough: this.snapshotState.allocatedThrough + 1,
      });
      if (!Number.isSafeInteger(candidate.allocatedThrough))
        throw new Error("PI ID space exhausted");
      await this.persist(candidate, []);
      this.snapshotState = candidate;
      return candidate.allocatedThrough as I;
    });
  }
  override commit(
    writes: readonly StorageWrite[],
    context: Context,
  ): Promise<Seq> {
    return this.serialize(async () => {
      context.abortSignal?.throwIfAborted();
      const prepared = this.prepareCommit(writes);
      // Prior commits are immutable; clone only the new native writes.
      const candidate = immutable({
        ...this.snapshotState,
        commits: [
          ...this.snapshotState.commits,
          {
            sequence: prepared.seq,
            writes: structuredClone([...prepared.writes]),
          },
        ],
      });
      await this.persist(candidate, prepared.writes);
      // Never expose uncommitted native state after a lost or rejected persistence acknowledgement.
      const sequence = prepared.apply();
      this.snapshotState = candidate;
      return sequence;
    });
  }
}
