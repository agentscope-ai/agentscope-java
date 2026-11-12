import { ServiceError, TurnRejected } from "./index.js";

export type FailureKind = "ownership" | "configuration" | "transient";
/** Never classify an uncertain external effect as a terminal business failure. */
export function classifyFailure(error: unknown): FailureKind {
  if (error instanceof ServiceError) {
    if (
      [
        "execution_busy",
        "execution_fenced",
        "stale_execution",
        "stale_resume",
        "lease_expired",
        "turn_settled",
        "queue_version_conflict",
        "no_eligible_turn",
        "brain_revision_conflict",
      ].includes(error.code)
    )
      return "ownership";
    if (
      [400, 401, 403, 404, 413, 422].includes(error.status) ||
      [
        "harness_type_mismatch",
        "native_format_mismatch",
        "recovery_limit_exceeded",
        "request_id_conflict",
      ].includes(error.code)
    )
      return "configuration";
  }
  return error instanceof TurnRejected ? "configuration" : "transient";
}

/** A bounded cache: server-side deferrals retain not-before times across Worker restarts. */
export class RetrySchedule {
  private readonly entries = new Map<
    string,
    { attempts: number; due: number }
  >();
  constructor(
    readonly baseMs = 1000,
    readonly maximumMs = 300000,
    readonly budget = 8,
    private readonly random = Math.random,
    private readonly now = Date.now,
  ) {}
  ready(key: string): boolean {
    return (this.entries.get(key)?.due ?? 0) <= this.now();
  }
  success(key: string): void {
    this.entries.delete(key);
  }
  failure(key: string): {
    attempts: number;
    retryAfterMs: number;
    exhausted: boolean;
  } {
    const attempts = (this.entries.get(key)?.attempts ?? 0) + 1;
    const exhausted = attempts >= this.budget;
    const cap = Math.min(
      this.maximumMs,
      this.baseMs * 2 ** Math.min(attempts - 1, 30),
    );
    const retryAfterMs = exhausted
      ? this.maximumMs
      : Math.ceil(cap * (0.5 + this.random() / 2));
    this.entries.delete(key);
    this.entries.set(key, { attempts, due: this.now() + retryAfterMs });
    if (this.entries.size > 4096)
      this.entries.delete(this.entries.keys().next().value!);
    return { attempts, retryAfterMs, exhausted };
  }
}
