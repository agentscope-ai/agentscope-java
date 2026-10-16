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

import io.agentscope.core.tool.circuitbreaker.ToolCircuitBreakerConfig;
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
 * <p>One key per tool, {@code {prefix}{tool}}, holding a versioned snapshot:
 *
 * <pre>
 *   "v1:&lt;failureCount&gt;:&lt;generation&gt;:&lt;openedAt&gt;:&lt;probeLeaseUntil&gt;:&lt;tokenLength&gt;:&lt;probeToken&gt;"
 * </pre>
 *
 * <p>Keeping the entire state in one key is what lets a complete transition be one compare-and-set,
 * and it means every script touches a single key — so the store needs no hash tags and works
 * unchanged on Redis Cluster. A missing key is the encoding of {@link ToolCircuitSnapshot#CLOSED},
 * so a recovered tool leaves nothing behind.
 * The token length counts Java string characters; {@code -1} represents null and {@code 0} an empty
 * token. The final field may contain any characters, including colons. Legacy five-field values are
 * migrated on read without extending their remaining TTL. Older implementations cannot read the
 * versioned format, so upgrade all replicas sharing a prefix together.
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
 * <p>Non-closed states carry a TTL so tools that misbehave once do not accumulate state forever.
 * Creating a breaker validates that the effective Redis TTL exceeds both its maximum cooldown and
 * probe timeout: expiring either state early would bypass the configured isolation or admit another
 * probe. The default of 24h clears the default cooldown and probe timeout by a wide margin.
 *
 * <p>All stores with the same prefix share circuit state. Assign a distinct prefix per environment,
 * tenant or tool namespace when equally named tools must have independent circuits.
 */
public class RedisToolCircuitBreakerStore implements ToolCircuitBreakerStore {

    private static final Logger logger =
            LoggerFactory.getLogger(RedisToolCircuitBreakerStore.class);

    private static final String DEFAULT_KEY_PREFIX = "agentscope:tool-cb:";
    private static final Duration DEFAULT_TTL = Duration.ofHours(24);

    /** Encoding of {@link ToolCircuitSnapshot#CLOSED}: an absent key. */
    private static final String ABSENT = "";

    private static final String FORMAT_VERSION = "v1:";

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

    /** Canonicalize only the exact bytes read, preserving a concurrent transition and the TTL. */
    private static final String CANONICALIZE_SCRIPT =
            "local current = redis.call('GET', KEYS[1]) "
                    + "if current ~= ARGV[1] then return 0 end "
                    + "if ARGV[2] == '' then "
                    + "  redis.call('DEL', KEYS[1]) "
                    + "else "
                    + "  local ttl = redis.call('PTTL', KEYS[1]) "
                    + "  if ttl == -2 then return 0 end "
                    + "  if ttl == -1 then "
                    + "    redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3]) "
                    + "  else "
                    + "    redis.call('SET', KEYS[1], ARGV[2], 'PX', math.max(1, ttl)) "
                    + "  end "
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
     * @param stateTtl how long non-closed state is retained; must be positive. Whole seconds are used,
     *     with a minimum of one second. The effective TTL must exceed the breaker's maximum cooldown
     *     and probe timeout, which is checked when the breaker is created.
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
    public void validateConfig(ToolCircuitBreakerConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        Duration ttl = Duration.ofSeconds(ttlSeconds);
        Duration required =
                config.getMaxCooldown().compareTo(config.getProbeTimeout()) >= 0
                        ? config.getMaxCooldown()
                        : config.getProbeTimeout();
        if (ttl.compareTo(required) <= 0) {
            throw new IllegalArgumentException(
                    "Redis state TTL ("
                            + ttl
                            + ") must exceed both maxCooldown ("
                            + config.getMaxCooldown()
                            + ") and probeTimeout ("
                            + config.getProbeTimeout()
                            + ")");
        }
    }

    @Override
    public ToolCircuitSnapshot snapshot(String toolName) {
        String key = circuitKey(toolName);
        while (true) {
            String raw = client.get(key);
            ToolCircuitSnapshot snapshot = decode(toolName, raw);
            String canonical = encode(snapshot);
            if (raw == null || (raw.equals(canonical) && !raw.isEmpty())) {
                return snapshot;
            }
            // Decode and CAS must agree on one encoding. Otherwise an unreadable value reported as
            // CLOSED, or a parseable noncanonical value, makes every subsequent transition lose
            // CAS.
            if (client.evalScript(
                            CANONICALIZE_SCRIPT,
                            List.of(key),
                            List.of(raw, canonical, Long.toString(ttlSeconds)))
                    == 1L) {
                return snapshot;
            }
            // A writer won the repair race. Read its snapshot instead of discarding its transition.
        }
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
        Objects.requireNonNull(snapshot, "snapshot must not be null");
        if (ToolCircuitSnapshot.CLOSED.equals(snapshot)) {
            return ABSENT;
        }
        String token = snapshot.probeToken() == null ? "" : snapshot.probeToken();
        return FORMAT_VERSION
                + snapshot.failureCount()
                + ":"
                + snapshot.generation()
                + ":"
                + snapshot.openedAtEpochMilli()
                + ":"
                + snapshot.probeLeaseUntilEpochMilli()
                + ":"
                + (snapshot.probeToken() == null ? -1 : token.length())
                + ":"
                + token;
    }

    /**
     * Decode a stored value. Anything unreadable is treated as closed rather than withholding a tool
     * forever on the strength of state nobody can interpret.
     */
    private ToolCircuitSnapshot decode(String toolName, String value) {
        if (value == null || value.isEmpty()) {
            return ToolCircuitSnapshot.CLOSED;
        }
        try {
            String[] parts;
            long failureCount;
            long generation;
            long openedAt;
            String token;
            long probeLease;
            if (value.startsWith(FORMAT_VERSION)) {
                parts = value.split(":", 7);
                if (parts.length != 7) {
                    throw new IllegalArgumentException("incomplete versioned snapshot");
                }
                failureCount = Long.parseLong(parts[1]);
                generation = Long.parseLong(parts[2]);
                openedAt = Long.parseLong(parts[3]);
                probeLease = Long.parseLong(parts[4]);
                int tokenLength = Integer.parseInt(parts[5]);
                if (tokenLength < -1
                        || (tokenLength == -1 && !parts[6].isEmpty())
                        || (tokenLength >= 0 && tokenLength != parts[6].length())) {
                    throw new IllegalArgumentException("invalid token length");
                }
                token = tokenLength == -1 ? null : parts[6];
            } else {
                parts = value.split(":", -1);
                if (parts.length != 5) {
                    throw new IllegalArgumentException("incomplete legacy snapshot");
                }
                failureCount = Long.parseLong(parts[0]);
                generation = Long.parseLong(parts[1]);
                openedAt = Long.parseLong(parts[2]);
                token = parts[3].isEmpty() ? null : parts[3];
                probeLease = Long.parseLong(parts[4]);
            }
            if (failureCount < 0L || generation < 0L || openedAt < 0L || probeLease < 0L) {
                throw new IllegalArgumentException("negative snapshot field");
            }
            return new ToolCircuitSnapshot(failureCount, generation, openedAt, token, probeLease);
        } catch (IllegalArgumentException e) {
            logger.warn(
                    "Repairing unreadable circuit state for tool={}, value={}. Treating the circuit"
                            + " as closed.",
                    toolName,
                    value);
            return ToolCircuitSnapshot.CLOSED;
        }
    }

    private String circuitKey(String toolName) {
        return keyPrefix + Objects.requireNonNull(toolName, "toolName must not be null");
    }
}
