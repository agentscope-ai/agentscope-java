import { createServer } from "node:http";
import { hostname } from "node:os";
import { randomUUID } from "node:crypto";
import {
  SessionClient,
  ServiceError,
  UnconfirmedResponse,
  delay,
  runClaimed,
  type HarnessAdapter,
  type Scope,
  type Fence,
} from "./index.js";
import { classifyFailure, RetrySchedule } from "./retry.js";
import { decodeConfiguration } from "./configuration.js";

interface Delivery {
  scope: Scope;
  turnId: string;
  trigger: unknown;
}
interface Claim {
  scope: Scope;
  turnId: string;
  fence: Fence;
  expiresAt: string;
}
export interface WorkerOptions {
  env?: NodeJS.ProcessEnv;
  signal?: AbortSignal;
  /** Embedders can collect structured events without exposing error bodies or credentials. */
  log?: (event: Record<string, unknown>) => void;
}
function integer(
  env: NodeJS.ProcessEnv,
  key: string,
  fallback: number,
  min: number,
  max: number,
): number {
  const value = Number(env[key] ?? fallback);
  if (!Number.isInteger(value) || value < min || value > max)
    throw new Error(`${key} must be ${min}..${max}`);
  return value;
}
async function within(
  work: Promise<unknown>,
  milliseconds: number,
): Promise<boolean> {
  let timer: NodeJS.Timeout | undefined;
  try {
    return await Promise.race([
      work.then(() => true),
      new Promise<boolean>((resolve) => {
        timer = setTimeout(() => resolve(false), milliseconds);
      }),
    ]);
  } finally {
    if (timer) clearTimeout(timer);
  }
}
/** Session Service owns DB/MQ; shutdown stops admission before draining active executions. */
export async function runWorker(
  adapter: HarnessAdapter,
  options: WorkerOptions = {},
): Promise<void> {
  const env = options.env ?? process.env;
  const url = env.SESSION_SERVICE_URL,
    token = env.MANAGED_AGENTS_INTERNAL_TOKEN;
  if (!url || !token)
    throw new Error(
      "SESSION_SERVICE_URL and MANAGED_AGENTS_INTERNAL_TOKEN are required",
    );
  const client = new SessionClient(
    url,
    token,
    integer(env, "BRAIN_HTTP_TIMEOUT_MS", 30000, 100, 300000),
  );
  const concurrency = integer(env, "BRAIN_CONCURRENCY", 4, 1, 64);
  const inlineWaitMs = integer(env, "BRAIN_INLINE_WAIT_MS", 1000, 0, 600000);
  const drainMs = integer(env, "BRAIN_DRAIN_MS", 30000, 0, 300000);
  const abortGraceMs = integer(env, "BRAIN_ABORT_GRACE_MS", 2000, 1, 30000);
  const pollMs = integer(env, "BRAIN_POLL_MS", 500, 10, 30000);
  const retry = new RetrySchedule(
    integer(env, "BRAIN_RETRY_BASE_MS", 1000, 1, 300000),
    integer(env, "BRAIN_RETRY_MAX_MS", 300000, 1, 300000),
    integer(env, "BRAIN_RETRY_BUDGET", 8, 1, 100),
  );
  if (retry.baseMs > retry.maximumMs)
    throw new Error("BRAIN_RETRY_BASE_MS exceeds BRAIN_RETRY_MAX_MS");
  const port = integer(env, "PORT", 8082, 0, 65535);
  const log =
    options.log ??
    ((event) => process.stderr.write(JSON.stringify(event) + "\n"));
  const polling = new AbortController(),
    execution = new AbortController();
  const active = new Map<string, Promise<void>>();
  const counters = {
    claimed: 0,
    completed: 0,
    retries: 0,
    ownership_conflicts: 0,
    deferral_failures: 0,
  };
  const owner = {
    instanceId: env.BRAIN_INSTANCE_ID ?? hostname(),
    incarnationId: randomUUID(),
  };
  let serviceReady = false,
    fatal: unknown;
  const stop = () => polling.abort(new Error("Worker is draining"));
  const fail = (error: unknown) => {
    fatal ??= error;
    stop();
    execution.abort(error);
  };
  process.once("SIGTERM", stop);
  process.once("SIGINT", stop);
  options.signal?.addEventListener("abort", stop, { once: true });
  if (options.signal?.aborted) stop();
  const health = createServer((request, response) => {
    if (!["/healthz", "/readyz", "/metrics"].includes(request.url ?? "")) {
      response.writeHead(404).end();
      return;
    }
    const ready = serviceReady && !polling.signal.aborted;
    if (request.url === "/metrics") {
      response
        .writeHead(200, { "content-type": "text/plain; version=0.0.4" })
        .end(
          [
            `managed_brain_active ${active.size}`,
            `managed_brain_ready ${Number(ready)}`,
            ...Object.entries(counters).map(
              ([name, value]) => `managed_brain_${name}_total ${value}`,
            ),
          ].join("\n") + "\n",
        );
      return;
    }
    response
      .writeHead(request.url === "/readyz" && !ready ? 503 : 200, {
        "content-type": "application/json",
      })
      .end(
        JSON.stringify({
          harness: adapter.type,
          active: active.size,
          draining: polling.signal.aborted,
          serviceReady,
        }),
      );
  });
  try {
    await new Promise<void>((resolve, reject) => {
      health.once("error", reject);
      health.listen(port, "0.0.0.0", resolve);
    });
    while (!polling.signal.aborted) {
      try {
        // Also poll at capacity to keep dependency readiness current. Never claim beyond capacity.
        const page = await client.post<{ deliveries: Delivery[] }>(
          "executions/next",
          {
            protocolVersion: "0.1.0",
            harnessType: adapter.type,
            limit: Math.max(1, concurrency - active.size),
          },
          polling.signal,
        );
        serviceReady = true;
        retry.success("poll");
        for (const delivery of page.deliveries) {
          if (polling.signal.aborted || active.size >= concurrency) break;
          const scope = JSON.stringify(delivery.scope),
            key = scope + ":" + delivery.turnId;
          if (active.has(scope) || !retry.ready(key)) continue;
          let claim: Claim | undefined;
          const run = (async () => {
            const request = {
              protocolVersion: "0.1.0",
              ...delivery,
              harnessType: adapter.type,
              claimId: randomUUID(),
              owner,
            };
            try {
              claim = await client.post<Claim>(
                "executions/claim",
                request,
                execution.signal,
              );
            } catch (error) {
              if (!(error instanceof UnconfirmedResponse)) throw error;
              claim = await client.post<Claim>(
                "executions/claim",
                request,
                execution.signal,
              );
            }
            counters.claimed++;
            const response = await client.post<unknown>(
              "executions/configuration",
              {
                protocolVersion: "0.1.0",
                scope: claim.scope,
                turnId: claim.turnId,
                fence: claim.fence,
              },
              execution.signal,
            );
            const configuration = decodeConfiguration(response, claim.scope, claim.turnId, adapter.type);
            await runClaimed(
              adapter,
              client,
              claim,
              {
                ...configuration,
                inlineWaitMs:
                  configuration.inlineWaitMs ?? inlineWaitMs,
              },
              execution.signal,
            );
            counters.completed++;
            retry.success(key);
          })()
            .catch(async (error) => {
              if (execution.signal.aborted) return;
              const kind = classifyFailure(error);
              const fields = {
                scope: delivery.scope,
                turnId: delivery.turnId,
                harness: adapter.type,
                code:
                  error instanceof ServiceError
                    ? error.code
                    : error instanceof Error
                      ? error.name
                      : "Error",
                kind,
              };
              if (kind === "configuration") {
                log({ event: "worker.configuration_error", ...fields });
                fail(error);
                return;
              }
              // A competing owner must not have its scheduling delayed by this Worker.
              if (kind === "ownership") {
                counters.ownership_conflicts++;
                log({ event: "execution.ownership_lost", ...fields });
                return;
              }
              counters.retries++;
              const scheduled = retry.failure(key);
              log({
                event: scheduled.exhausted
                  ? "execution.retry_budget_exhausted"
                  : "execution.retry",
                ...fields,
                ...scheduled,
              });
              const deferral = {
                protocolVersion: "0.1.0",
                ...delivery,
                deferralId: randomUUID(),
                ...(claim ? { fence: claim.fence } : {}),
                retryAfterMs: scheduled.retryAfterMs,
                reason: "temporarily_unavailable",
              };
              try {
                try {
                  await client.post(
                    "deliveries/defer",
                    deferral,
                    execution.signal,
                  );
                } catch (uncertain) {
                  if (!(uncertain instanceof UnconfirmedResponse))
                    throw uncertain;
                  await client.post(
                    "deliveries/defer",
                    deferral,
                    execution.signal,
                  );
                }
              } catch {
                counters.deferral_failures++;
                log({ event: "execution.deferral_unconfirmed", ...fields });
              }
            })
            .finally(() => active.delete(scope));
          active.set(scope, run);
        }
        await delay(pollMs, polling.signal);
      } catch (error) {
        if (polling.signal.aborted) break;
        serviceReady = false;
        const kind = classifyFailure(error);
        if (kind === "configuration") {
          log({ event: "worker.configuration_error", kind });
          fail(error);
          break;
        }
        const scheduled = retry.failure("poll");
        log({ event: "worker.retry", kind, ...scheduled });
        await delay(scheduled.retryAfterMs, polling.signal).catch(() => {});
      }
    }
  } finally {
    stop();
    const complete = Promise.allSettled(active.values());
    if (!(await within(complete, fatal ? 0 : drainMs))) {
      log({ event: "worker.drain_deadline", active: active.size });
      execution.abort(new Error("Worker drain deadline exceeded"));
      if (!(await within(complete, abortGraceMs)))
        fatal ??= new Error("Worker shutdown timed out");
    }
    execution.abort(new Error("Worker stopped"));
    process.removeListener("SIGTERM", stop);
    process.removeListener("SIGINT", stop);
    options.signal?.removeEventListener("abort", stop);
    health.closeAllConnections();
    await new Promise<void>((resolve) => health.close(() => resolve()));
  }
  if (fatal) throw fatal;
}
