/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.harness.agent.sandbox;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.params.SetParams;

/**
 * Redis-backed {@link SandboxExecutionGuard} that serialises concurrent access to a sandbox
 * isolation slot using a Redis {@code SET NX PX} lease.
 *
 * <h2>Mechanism</h2>
 *
 * <ol>
 *   <li>{@link #tryEnter} writes a unique token to a Redis key with {@code SET NX PX <ttlMs>}.
 *       If the key is already held by another caller, it retries at {@code retryInterval} intervals
 *       until the slot becomes free or the thread is interrupted.
 *   <li>The returned {@link SandboxLease#close()} executes a Lua CAS script that deletes the key
 *       only when the stored value equals the caller's token, preventing another holder's lock from
 *       being released by mistake.
 * </ol>
 *
 * <h2>Key format</h2>
 *
 * <pre>{@code <keyPrefix><scope_lower>:<value>}</pre>
 *
 * <p>Examples (with default prefix {@code agentscope:sandbox:lock:}):
 *
 * <ul>
 *   <li>{@code agentscope:sandbox:lock:agent:code-agent}
 *   <li>{@code agentscope:sandbox:lock:global:__global__}
 * </ul>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * UnifiedJedis jedis = new JedisPooled("localhost", 6379);
 *
 * SandboxExecutionGuard guard = RedisSandboxExecutionGuard.builder(jedis)
 *     .leaseTtl(Duration.ofMinutes(30))   // must exceed worst-case call duration
 *     .retryInterval(Duration.ofMillis(500))
 *     .build();
 *
 * HarnessAgent.builder()
 *     .filesystem(new DockerFilesystemSpec()
 *         .isolationScope(IsolationScope.AGENT)
 *         .executionGuard(guard))
 *     ...
 *     .build();
 * }</pre>
 *
 * <h2>TTL guidance</h2>
 *
 * <p>Set {@code leaseTtl} to comfortably exceed the worst-case agent call duration (including
 * retries and LLM latency). If a call outlasts the TTL, Redis will evict the key and a concurrent
 * caller may enter — this is a safety valve to prevent permanent deadlock, not an indication that
 * two callers will safely share state. Monitor call durations and size the TTL accordingly.
 *
 * <h2>Extending this guard</h2>
 *
 * <p>This class serves as the canonical reference implementation for {@link SandboxExecutionGuard}.
 * Alternative backends (ZooKeeper, etcd, database advisory locks, …) should follow the same
 * contract:
 *
 * <ul>
 *   <li>{@link #tryEnter} blocks until the slot is acquired or the thread is interrupted.
 *   <li>The returned {@link SandboxLease} releases the slot idempotently on {@link
 *       SandboxLease#close()}.
 *   <li>Release failures must be logged and swallowed — never thrown — so the harness post-call
 *       path always completes.
 * </ul>
 */
public final class RedisSandboxExecutionGuard implements SandboxExecutionGuard {

    private static final Logger log = LoggerFactory.getLogger(RedisSandboxExecutionGuard.class);

    /**
     * Lua script: atomically delete {@code KEYS[1]} only when its value equals {@code ARGV[1]}.
     * Returns 1 on success, 0 if the key was already gone or held by a different token.
     */
    private static final String RELEASE_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "    return redis.call('del', KEYS[1]) "
                    + "else "
                    + "    return 0 "
                    + "end";

    private final UnifiedJedis jedis;
    private final String keyPrefix;
    private final long leaseTtlMs;
    private final long retryIntervalMs;

    private RedisSandboxExecutionGuard(Builder builder) {
        this.jedis = builder.jedis;
        this.keyPrefix = builder.keyPrefix;
        this.leaseTtlMs = builder.leaseTtlMs;
        this.retryIntervalMs = builder.retryIntervalMs;
    }

    /**
     * Creates a builder for this guard.
     *
     * @param jedis initialized Jedis client; the same instance used for
     *              {@link io.agentscope.harness.agent.sandbox.snapshot.RedisSnapshotSpec} is fine
     */
    public static Builder builder(UnifiedJedis jedis) {
        return new Builder(jedis);
    }

    /**
     * Acquires the Redis-backed lease for {@code key}, spinning at {@code retryInterval} until
     * the slot is free.
     *
     * @throws InterruptedException if the calling thread is interrupted while waiting
     */
    @Override
    public SandboxLease tryEnter(SandboxIsolationKey key) throws InterruptedException {
        String redisKey = composeKey(key);
        String token = UUID.randomUUID().toString();
        SetParams params = SetParams.setParams().nx().px(leaseTtlMs);

        log.debug(
                "[sandbox-guard] Acquiring Redis lease for key={} ttlMs={}", redisKey, leaseTtlMs);

        while (true) {
            String result = jedis.set(redisKey, token, params);
            if ("OK".equals(result)) {
                log.debug("[sandbox-guard] Acquired Redis lease for key={}", redisKey);
                return () -> releaseLock(redisKey, token);
            }
            if (Thread.interrupted()) {
                throw new InterruptedException(
                        "Interrupted while waiting for sandbox execution guard on " + redisKey);
            }
            Thread.sleep(retryIntervalMs);
        }
    }

    private void releaseLock(String redisKey, String token) {
        try {
            Object result = jedis.eval(RELEASE_SCRIPT, List.of(redisKey), List.of(token));
            if (Long.valueOf(0L).equals(result)) {
                log.warn(
                        "[sandbox-guard] Redis lease for key={} was already gone or held by a"
                                + " different token — possible TTL expiry during a long call",
                        redisKey);
            } else {
                log.debug("[sandbox-guard] Released Redis lease for key={}", redisKey);
            }
        } catch (Exception e) {
            log.warn(
                    "[sandbox-guard] Failed to release Redis lease for key={}: {}",
                    redisKey,
                    e.getMessage(),
                    e);
        }
    }

    private String composeKey(SandboxIsolationKey key) {
        return keyPrefix + key.getScope().name().toLowerCase() + ":" + key.getValue();
    }

    // -------------------------------------------------------------------------
    // Builder
    // -------------------------------------------------------------------------

    /** Builder for {@link RedisSandboxExecutionGuard}. */
    public static final class Builder {

        private static final Duration DEFAULT_LEASE_TTL = Duration.ofMinutes(30);
        private static final Duration DEFAULT_RETRY_INTERVAL = Duration.ofMillis(500);
        private static final String DEFAULT_KEY_PREFIX = "agentscope:sandbox:lock:";

        private final UnifiedJedis jedis;
        private String keyPrefix = DEFAULT_KEY_PREFIX;
        private long leaseTtlMs = DEFAULT_LEASE_TTL.toMillis();
        private long retryIntervalMs = DEFAULT_RETRY_INTERVAL.toMillis();

        private Builder(UnifiedJedis jedis) {
            this.jedis = Objects.requireNonNull(jedis, "jedis must not be null");
        }

        /**
         * Sets the Redis key prefix used for lock keys.
         *
         * <p>Default: {@code "agentscope:sandbox:lock:"}. Useful when multiple environments or
         * tenants share the same Redis instance and need namespace separation.
         *
         * @param keyPrefix the key prefix; must not be null or blank
         */
        public Builder keyPrefix(String keyPrefix) {
            if (keyPrefix == null || keyPrefix.isBlank()) {
                throw new IllegalArgumentException("keyPrefix must not be blank");
            }
            this.keyPrefix = keyPrefix.endsWith(":") ? keyPrefix : keyPrefix + ":";
            return this;
        }

        /**
         * Sets the TTL for each Redis lock key.
         *
         * <p>Default: {@code 30 minutes}. Must exceed the worst-case agent call duration
         * (including LLM latency, retries, and snapshot operations). When a call outlasts the TTL
         * the key is evicted and the next waiter may enter — this is a safety valve, not a
         * correctness guarantee.
         *
         * @param ttl the lease TTL; must be positive
         */
        public Builder leaseTtl(Duration ttl) {
            Objects.requireNonNull(ttl, "leaseTtl must not be null");
            if (ttl.isNegative() || ttl.isZero()) {
                throw new IllegalArgumentException("leaseTtl must be positive");
            }
            this.leaseTtlMs = ttl.toMillis();
            return this;
        }

        /**
         * Sets the polling interval between lock acquisition attempts.
         *
         * <p>Default: {@code 500 ms}. Lower values reduce latency at the cost of more Redis
         * round-trips; higher values reduce load at the cost of increased queuing delay.
         *
         * @param interval the retry interval; must be positive
         */
        public Builder retryInterval(Duration interval) {
            Objects.requireNonNull(interval, "retryInterval must not be null");
            if (interval.isNegative() || interval.isZero()) {
                throw new IllegalArgumentException("retryInterval must be positive");
            }
            this.retryIntervalMs = interval.toMillis();
            return this;
        }

        /** Builds the {@link RedisSandboxExecutionGuard}. */
        public RedisSandboxExecutionGuard build() {
            return new RedisSandboxExecutionGuard(this);
        }
    }
}
