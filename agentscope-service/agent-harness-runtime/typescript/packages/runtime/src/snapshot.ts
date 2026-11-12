import {
  canonicalHash,
  sha256,
  ServiceError,
  type Json,
  type NativeState,
  type ObjectRef,
  type Scope,
  type SessionClient,
} from "./index.js";
export type SnapshotArray = "entries" | "commits";
const manifestFormat = "managed-pi-snapshot-v1",
  segmentFormat = "managed-pi-segment-v1";
const maxBytes = 512 * 1024 * 1024,
  maxSegments = 1024;
interface Manifest {
  version: "managed-pi-segments-v1";
  stateFormat: string;
  field: SnapshotArray;
  metadata: Record<string, Json>;
  counts: number[];
  byteLength: number;
}
/** Full logical checkpoints with immutable array segments; every dependency is in journalRefs. */
export class NativeSnapshots {
  private cache = new Map<string, ObjectRef>();
  constructor(
    private readonly client: SessionClient,
    private readonly scope: Scope,
    private readonly format: string,
    private readonly signal: AbortSignal,
  ) {}
  async write(
    value: Json,
    field?: SnapshotArray,
  ): Promise<Pick<NativeState, "checkpointRef" | "journalRefs">> {
    const object = value as Record<string, Json>;
    const history =
      field && object && !Array.isArray(object) ? object[field] : undefined;
    if (!field || !Array.isArray(history))
      return {
        checkpointRef: await this.client.put(
          this.scope,
          Buffer.from(JSON.stringify(value)),
          this.format,
          this.signal,
        ),
      };
    if (history.length < 128) {
      const bytes = Buffer.from(JSON.stringify(value));
      if (bytes.length <= 256 * 1024)
        return {
          checkpointRef: await this.client.put(
            this.scope,
            bytes,
            this.format,
            this.signal,
          ),
        };
    }
    const metadata = { ...object };
    delete metadata[field];
    const refs: ObjectRef[] = [],
      counts: number[] = [],
      current = new Map<string, ObjectRef>();
    let parts: string[] = [],
      size = 2,
      total = 0;
    const flush = async () => {
      if (!parts.length) return;
      if (refs.length >= maxSegments)
        throw new ServiceError(413, "native_snapshot_limit_exceeded");
      const bytes = Buffer.from(`[${parts.join(",")}]`),
        hash = sha256(bytes);
      total += bytes.length;
      if (total > maxBytes)
        throw new ServiceError(413, "native_snapshot_limit_exceeded");
      const ref =
        this.cache.get(hash) ??
        (await this.client.put(this.scope, bytes, segmentFormat, this.signal));
      refs.push(ref);
      counts.push(parts.length);
      current.set(hash, ref);
      parts = [];
      size = 2;
    };
    for (const entry of history) {
      const encoded = JSON.stringify(entry),
        bytes = Buffer.byteLength(encoded);
      if (bytes > 8 * 1024 * 1024)
        throw new ServiceError(413, "native_entry_limit_exceeded");
      if (
        parts.length &&
        (parts.length >= 128 || size + bytes + 1 > 512 * 1024)
      )
        await flush();
      parts.push(encoded);
      size += bytes + 1;
    }
    await flush();
    const manifest: Manifest = {
      version: "managed-pi-segments-v1",
      stateFormat: this.format,
      field,
      metadata,
      counts,
      byteLength: total,
    };
    const encoded = Buffer.from(JSON.stringify(manifest));
    if (encoded.length > 512 * 1024)
      throw new ServiceError(413, "native_metadata_limit_exceeded");
    const checkpointRef = await this.client.put(
      this.scope,
      encoded,
      manifestFormat,
      this.signal,
    );
    this.cache = current;
    return { checkpointRef, ...(refs.length ? { journalRefs: refs } : {}) };
  }
  async read(state: NativeState): Promise<Json> {
    if (!state.checkpointRef)
      throw new Error("Adapter requires a complete native snapshot");
    this.checkScope(state.checkpointRef);
    const parsed = JSON.parse(
      Buffer.from(
        await this.client.read(state.checkpointRef, this.signal),
      ).toString("utf8"),
    );
    if (state.checkpointRef.formatVersion !== manifestFormat) {
      if (state.checkpointRef.formatVersion !== this.format)
        throw new Error("Unsupported native snapshot encoding");
      return parsed as Json;
    }
    const manifest = parsed as Manifest,
      refs = state.journalRefs ?? [];
    if (
      manifest.version !== "managed-pi-segments-v1" ||
      manifest.stateFormat !== this.format ||
      !["entries", "commits"].includes(manifest.field) ||
      !Array.isArray(manifest.counts) ||
      manifest.counts.length !== refs.length ||
      refs.length > maxSegments ||
      !Number.isSafeInteger(manifest.byteLength) ||
      manifest.byteLength < 0 ||
      manifest.byteLength > maxBytes ||
      !manifest.metadata ||
      typeof manifest.metadata !== "object" ||
      Array.isArray(manifest.metadata) ||
      manifest.field in manifest.metadata
    )
      throw new Error("Invalid native snapshot manifest");
    const history: Json[] = [],
      current = new Map<string, ObjectRef>();
    let total = 0;
    for (let i = 0; i < refs.length; i++) {
      const ref = refs[i]!;
      this.checkScope(ref);
      if (
        ref.formatVersion !== segmentFormat ||
        !Number.isSafeInteger(manifest.counts[i]) ||
        manifest.counts[i]! < 1 ||
        manifest.counts[i]! > 128
      )
        throw new Error("Invalid native segment reference");
      const bytes = await this.client.read(ref, this.signal);
      total += bytes.length;
      if (total > maxBytes)
        throw new ServiceError(413, "native_snapshot_limit_exceeded");
      const entries = JSON.parse(Buffer.from(bytes).toString("utf8"));
      if (!Array.isArray(entries) || entries.length !== manifest.counts[i])
        throw new Error("Invalid native segment length");
      history.push(...entries);
      current.set(ref.sha256, ref);
    }
    if (total !== manifest.byteLength)
      throw new Error("Invalid native snapshot size");
    this.cache = current;
    return { ...manifest.metadata, [manifest.field]: history };
  }
  private checkScope(ref: ObjectRef): void {
    if (canonicalHash(ref.scope) !== canonicalHash(this.scope))
      throw new Error("Cross-scope native snapshot");
  }
}
