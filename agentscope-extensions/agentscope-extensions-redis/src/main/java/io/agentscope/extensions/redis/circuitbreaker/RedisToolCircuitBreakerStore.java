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
package io.agentscope.extensions.redis.circuitbreaker;

import io.agentscope.core.tool.circuitbreaker.ToolCircuitBreakerStore;
import io.agentscope.core.tool.circuitbreaker.ToolCircuitSnapshot;
import io.agentscope.extensions.redis.state.RedisClientAdapter;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redis-backed {@link ToolCircuitBreakerStore}, giving every replica one shared view of a broken
 * tool.
 *
 * <p>With the in-process store each replica has to rediscover an outage for itself, so an N-replica
 * deployment sends roughly N times the failing traffic and burns N times the tokens before the tool
 * is withheld everywhere. Sharing the state through Redis means the first replica to trip a circuit
 * withholds the tool for all of them, and the state survives a restart or a rescheduled pod.
 *
 * <h2>Keys</h2>
 *
 * <p>Two keys per tool, both addressed individually so the store works unchanged on Redis Cluster —
 * no multi-key script needs its arguments to share a hash slot:
 *
 * <ul>
 *   <li>{@code {prefix}{tool}:fail} — consecutive failure counter
 *   <li>{@code {prefix}{tool}:circuit} — {@code "<generation>:<openedAtEpochMilli>"}; the key's
 *       presence is what marks the circuit open, so no separate flag can fall out of sync
 * </ul>
 *
 * <p>Encoding generation and timestamp in one value keeps {@link #snapshot(String)} — the hot read,
 * executed for every supervised tool on every reasoning turn — down to a single {@code GET}.
 *
 * <h2>Atomicity</h2>
 *
 * <p>{@link #recordFailure(String)} and {@link #open(String, long)} are Lua scripts, so their
 * read-modify-write steps cannot interleave. Doing {@code INCR} and {@code EXPIRE} as two round
 * trips would leave a counter without a TTL whenever the second call is lost, and computing the next
 * generation client-side would let two replicas tripping at once write the same generation.
 *
 * <h2>Expiry</h2>
 *
 * <p>Both keys carry a TTL so tools that misbehave once do not accumulate state forever. Keep the
 * TTL comfortably longer than the breaker's maximum cooldown: if an open circuit's key expires
 * mid-cooldown the tool is offered again early, which fails open — safe, but not what was
 * configured. The default of 24h clears the default 600s ceiling by a wide margin.
 */
public class RedisToolCircuitBreakerStore implements ToolCircuitBreakerStore {

    private static final Logger logger =
            LoggerFactory.getLogger(RedisToolCircuitBreakerStore.class);

    private static final String DEFAULT_KEY_PREFIX = "agentscope:tool-cb:";
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    private static final String FAILURE_SUFFIX = ":fail";
    private static final String CIRCUIT_SUFFIX = ":circuit";

    /**
     * Increment the failure counter and refresh its TTL in one step.
     *
     * <p>KEYS[1] = failure key; ARGV[1] = TTL seconds. Returns the new count.
     */
    private static final String INCREMENT_FAILURE_SCRIPT =
            "local count = redis.call('INCR', KEYS[1]) "
                    + "redis.call('EXPIRE', KEYS[1], ARGV[1]) "
                    + "return count";

    /**
     * Advance the generation and stamp the open instant in one step.
     *
     * <p>KEYS[1] = circuit key; ARGV[1] = open instant in epoch millis; ARGV[2] = TTL seconds.
     * Returns the new generation.
     */
    private static final String OPEN_NEXT_GENERATION_SCRIPT =
            "local current = redis.call('GET', KEYS[1]) local generation = 0 if current then  "
                + " local sep = string.find(current, ':', 1, true)   if sep then generation ="
                + " tonumber(string.sub(current, 1, sep - 1)) or 0 end end generation = generation"
                + " + 1 redis.call('SET', KEYS[1], generation .. ':' .. ARGV[1], 'EX', ARGV[2])"
                + " return generation";

    private final RedisClientAdapter client;
    private final String keyPrefix;
    private final long ttlSeconds;

    /**
     * Create a store with the default key prefix ({@code agentscope:tool-cb:}) and a 24h TTL.
     *
     * @param client Redis client adapter
     */
    public RedisToolCircuitBreakerStore(RedisClientAdapter client) {
        this(client, DEFAULT_KEY_PREFIX, DEFAULT_TTL);
    }

    /**
     * Create a store with an explicit key prefix and TTL.
     *
     * @param client Redis client adapter
     * @param keyPrefix prefix for every key, letting environments share one Redis instance
     * @param stateTtl how long unused state is retained; must be positive and should exceed the
     *     breaker's maximum cooldown
     */
    public RedisToolCircuitBreakerStore(
            RedisClientAdapter client, String keyPrefix, Duration stateTtl) {
        this.client = Objects.requireNonNull(client, "client must not be null");
        if (keyPrefix == null || keyPrefix.isBlank()) {
            throw new IllegalArgumentException("keyPrefix must not be blank");
        }
        if (stateTtl == null || stateTtl.isNegative() || stateTtl.isZero()) {
            throw new IllegalArgumentException("stateTtl must be positive, got " + stateTtl);
        }
        this.keyPrefix = keyPrefix;
        this.ttlSeconds = Math.max(1L, stateTtl.toSeconds());
    }

    @Override
    public long recordFailure(String toolName) {
        return client.evalScript(
                INCREMENT_FAILURE_SCRIPT,
                List.of(failureKey(toolName)),
                List.of(Long.toString(ttlSeconds)));
    }

    @Override
    public void resetFailures(String toolName) {
        client.deleteKeys(failureKey(toolName));
    }

    @Override
    public long failureCount(String toolName) {
        return parseLong(client.get(failureKey(toolName)));
    }

    @Override
    public long open(String toolName, long openedAtEpochMilli) {
        return client.evalScript(
                OPEN_NEXT_GENERATION_SCRIPT,
                List.of(circuitKey(toolName)),
                List.of(Long.toString(openedAtEpochMilli), Long.toString(ttlSeconds)));
    }

    @Override
    public void close(String toolName) {
        client.deleteKeys(circuitKey(toolName));
    }

    @Override
    public ToolCircuitSnapshot snapshot(String toolName) {
        String value = client.get(circuitKey(toolName));
        if (value == null || value.isEmpty()) {
            return ToolCircuitSnapshot.CLOSED;
        }
        int separator = value.indexOf(':');
        if (separator <= 0 || separator == value.length() - 1) {
            // Unreadable value: treat as closed rather than withholding a tool forever on the
            // strength of state nobody can interpret.
            logger.warn(
                    "Ignoring malformed circuit state for tool={}, value={}. Treating the circuit"
                            + " as closed.",
                    toolName,
                    value);
            return ToolCircuitSnapshot.CLOSED;
        }
        long generation = parseLong(value.substring(0, separator));
        long openedAt = parseLong(value.substring(separator + 1));
        if (generation <= 0L || openedAt <= 0L) {
            logger.warn(
                    "Ignoring out-of-range circuit state for tool={}, value={}. Treating the"
                            + " circuit as closed.",
                    toolName,
                    value);
            return ToolCircuitSnapshot.CLOSED;
        }
        return new ToolCircuitSnapshot(generation, openedAt);
    }

    private String failureKey(String toolName) {
        return keyPrefix + toolName + FAILURE_SUFFIX;
    }

    private String circuitKey(String toolName) {
        return keyPrefix + toolName + CIRCUIT_SUFFIX;
    }

    private static long parseLong(String value) {
        if (value == null || value.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
