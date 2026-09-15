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
 * withholds the tool for all of them, exactly one replica probes for recovery, and the state survives
 * a restart or a rescheduled pod.
 *
 * <h2>Key layout</h2>
 *
 * <p>One key per tool, {@code {prefix}{tool}}, holding the whole snapshot as a delimited value:
 *
 * <pre>
 *   "&lt;failureCount&gt;:&lt;generation&gt;:&lt;openedAt&gt;:&lt;probeToken&gt;:&lt;probeLeaseUntil&gt;"
 * </pre>
 *
 * <p>Keeping the entire state in one key is what lets a complete transition be one compare-and-set,
 * and it means every script touches a single key — so the store needs no hash tags and works
 * unchanged on Redis Cluster. A missing key is the encoding of {@link ToolCircuitSnapshot#CLOSED},
 * so a recovered tool leaves nothing behind.
 *
 * <h2>Atomicity</h2>
 *
 * <p>{@link #compareAndSet} compares the stored value against the caller's expected encoding and
 * replaces it in one Lua script. Doing the comparison client-side would reintroduce exactly the races
 * the breaker's compare-and-set protocol exists to remove: two replicas could each read the same
 * state and both commit a transition based on it.
 *
 * <h2>Expiry</h2>
 *
 * <p>Non-closed states carry a TTL so tools that misbehave once do not accumulate state forever. Keep
 * the TTL comfortably longer than the breaker's maximum cooldown: if an open circuit's key expires
 * mid-cooldown the tool is offered again early, which fails open — safe, but not what was configured.
 * The default of 24h clears the default 600s ceiling by a wide margin.
 */
public class RedisToolCircuitBreakerStore implements ToolCircuitBreakerStore {

    private static final Logger logger =
            LoggerFactory.getLogger(RedisToolCircuitBreakerStore.class);

    private static final String DEFAULT_KEY_PREFIX = "agentscope:tool-cb:";
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    /** Encoding of {@link ToolCircuitSnapshot#CLOSED}: an absent key. */
    private static final String ABSENT = "";

    private static final int FIELD_COUNT = 5;

    /**
     * Replace the stored value only if it still equals what the caller observed.
     *
     * <p>KEYS[1] = circuit key; ARGV[1] = expected encoding ({@code ""} for absent); ARGV[2] = new
     * encoding ({@code ""} to delete); ARGV[3] = TTL seconds. Returns 1 when committed, 0 when the
     * value had changed.
     */
    private static final String COMPARE_AND_SET_SCRIPT =
            "local current = redis.call('GET', KEYS[1]) "
                    + "if current == false then current = '' end "
                    + "if current ~= ARGV[1] then return 0 end "
                    + "if ARGV[2] == '' then "
                    + "  redis.call('DEL', KEYS[1]) "
                    + "else "
                    + "  redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3]) "
                    + "end "
                    + "return 1";

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
     * @param stateTtl how long non-closed state is retained; must be positive and should exceed the
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
    public ToolCircuitSnapshot snapshot(String toolName) {
        return decode(toolName, client.get(circuitKey(toolName)));
    }

    @Override
    public boolean compareAndSet(
            String toolName, ToolCircuitSnapshot expected, ToolCircuitSnapshot update) {
        return client.evalScript(
                        COMPARE_AND_SET_SCRIPT,
                        List.of(circuitKey(toolName)),
                        List.of(encode(expected), encode(update), Long.toString(ttlSeconds)))
                == 1L;
    }

    @Override
    public void reset(String toolName) {
        client.deleteKeys(circuitKey(toolName));
    }

    /**
     * Encode a snapshot, mapping CLOSED to the absent-key marker so "missing" and "closed" compare
     * equal.
     */
    private static String encode(ToolCircuitSnapshot snapshot) {
        if (snapshot == null || ToolCircuitSnapshot.CLOSED.equals(snapshot)) {
            return ABSENT;
        }
        String token = snapshot.probeToken() == null ? "" : snapshot.probeToken();
        return snapshot.failureCount()
                + ":"
                + snapshot.generation()
                + ":"
                + snapshot.openedAtEpochMilli()
                + ":"
                + token
                + ":"
                + snapshot.probeLeaseUntilEpochMilli();
    }

    /**
     * Decode a stored value. Anything unreadable is treated as closed rather than withholding a tool
     * forever on the strength of state nobody can interpret.
     */
    private ToolCircuitSnapshot decode(String toolName, String value) {
        if (value == null || value.isEmpty()) {
            return ToolCircuitSnapshot.CLOSED;
        }
        String[] parts = value.split(":", -1);
        if (parts.length != FIELD_COUNT) {
            logger.warn(
                    "Ignoring malformed circuit state for tool={}, value={}. Treating the circuit"
                            + " as closed.",
                    toolName,
                    value);
            return ToolCircuitSnapshot.CLOSED;
        }
        try {
            long failureCount = Long.parseLong(parts[0]);
            long generation = Long.parseLong(parts[1]);
            long openedAt = Long.parseLong(parts[2]);
            String token = parts[3].isEmpty() ? null : parts[3];
            long probeLease = Long.parseLong(parts[4]);
            if (failureCount < 0L || generation < 0L || openedAt < 0L || probeLease < 0L) {
                logger.warn(
                        "Ignoring out-of-range circuit state for tool={}, value={}. Treating the"
                                + " circuit as closed.",
                        toolName,
                        value);
                return ToolCircuitSnapshot.CLOSED;
            }
            return new ToolCircuitSnapshot(failureCount, generation, openedAt, token, probeLease);
        } catch (NumberFormatException e) {
            logger.warn(
                    "Ignoring unparsable circuit state for tool={}, value={}. Treating the circuit"
                            + " as closed.",
                    toolName,
                    value);
            return ToolCircuitSnapshot.CLOSED;
        }
    }

    private String circuitKey(String toolName) {
        return keyPrefix + toolName;
    }
}
