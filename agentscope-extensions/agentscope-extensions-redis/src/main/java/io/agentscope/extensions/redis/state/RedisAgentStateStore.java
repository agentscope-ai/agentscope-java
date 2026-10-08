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
package io.agentscope.extensions.redis.state;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.ListHashUtil;
import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.extensions.redis.state.jedis.JedisClientAdapter;
import io.agentscope.extensions.redis.state.lettuce.LettuceClientAdapter;
import io.agentscope.extensions.redis.state.redisson.RedissonClientAdapter;
import io.lettuce.core.RedisClient;
import io.lettuce.core.cluster.RedisClusterClient;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import redis.clients.jedis.UnifiedJedis;

/**
 * Redis-based session implementation supporting multiple Redis clients.
 *
 * <p>This implementation provides a unified interface for Redis-based session storage, supporting
 * multiple Redis client implementations:
 *
 * <ul>
 *   <li>Jedis - Standalone, Cluster, Sentinel</li>
 *   <li>Lettuce - Standalone, Cluster, Sentinel</li>
 *   <li>Redisson - Standalone, Cluster, Sentinel, Master/Slave</li>
 * </ul>
 *
 * <p>The session state is stored in Redis with following key structure (where
 * {@code {<user>/<session>}} is a Redis Cluster hash tag so all keys of a session share one slot):
 *
 * <ul>
 *   <li>Single state: {@code <prefix>{<user>/<session>}:<stateKey>} - Redis String containing JSON
 *   <li>List state: {@code <prefix>{<user>/<session>}:<stateKey>:list} - Redis List containing JSON items
 *   <li>List hash: {@code <prefix>{<user>/<session>}:<stateKey>:list:_hash} - Hash for change detection
 *   <li>AgentStateStore marker: {@code <prefix>{<user>/<session>}:_keys} - Redis Set tracking all state keys
 * </ul>
 *
 * <p><strong>Breaking change:</strong> the slot id is wrapped in a Redis Cluster hash tag
 * ({@code {...}}) so all keys of one session share one Cluster slot (required by the multi-key
 * Lua {@code EVAL}). Data written with the previous, un-tagged key layout is not readable and
 * must be migrated; the constructor detects legacy keys under the key prefix and logs a warning
 * pointing at {@link io.agentscope.extensions.redis.RedisKeyLayoutMigration}. Upgrade procedure:
 *
 * <pre>{@code
 * // 1. Plan: dry-run report, nothing written or deleted.
 * StateStoreMigrationReport plan = RedisKeyLayoutMigration.migrateAgentStateStore(
 *         clientAdapter, "agentscope:session:", true);
 * System.out.println(plan);
 * // 2. Migrate (run while the system is quiescent; per-key ops, cluster-safe):
 * StateStoreMigrationReport result = RedisKeyLayoutMigration.migrateAgentStateStore(
 *         clientAdapter, "agentscope:session:", false);
 * }</pre>
 *
 * <p>For {@code RedisStore} namespaces use
 * {@link io.agentscope.extensions.redis.RedisKeyLayoutMigration#migrateRedisStore(
 * redis.clients.jedis.UnifiedJedis, String, boolean)} analogously.
 *
 * <p><strong>Jedis Usage Examples:</strong></p>
 *
 * <p>Jedis Standalone (using RedisClient):
 *
 * <pre>{@code
 * // Create Jedis RedisClient (new API)
 * RedisClient redisClient = RedisClient.create("redis://localhost:6379");
 *
 * // Build RedisAgentStateStore
 * AgentStateStore stateStore = RedisAgentStateStore.builder()
 *     .jedisClient(redisClient)
 *     .build();
 * }</pre>
 *
 * <p>Jedis Cluster (using RedisClusterClient):
 *
 * <pre>{@code
 * // Create Jedis RedisClusterClient
 * Set<HostAndPort> nodes = new HashSet<>();
 * nodes.add(new HostAndPort("localhost", 7000));
 * nodes.add(new HostAndPort("localhost", 7001));
 * nodes.add(new HostAndPort("localhost", 7002));
 * RedisClusterClient redisClusterClient = RedisClusterClient.create(nodes);
 *
 * // Build RedisAgentStateStore
 * AgentStateStore stateStore = RedisAgentStateStore.builder()
 *     .jedisClient(redisClusterClient)
 *     .build();
 * }</pre>
 *
 * <p>Jedis Sentinel (using RedisSentinelClient):
 *
 * <pre>{@code
 * // Create Jedis RedisSentinelClient
 * Set<String> sentinelNodes = new HashSet<>();
 * sentinelNodes.add("localhost:26379");
 * sentinelNodes.add("localhost:26380");
 * RedisSentinelClient redisSentinelClient = RedisSentinelClient.create("mymaster", sentinelNodes);
 *
 * // Build RedisAgentStateStore
 * AgentStateStore stateStore = RedisAgentStateStore.builder()
 *     .jedisClient(redisSentinelClient)
 *     .build();
 * }</pre>
 *
 * <p><strong>Lettuce Usage Examples:</strong></p>
 *
 * <p>Lettuce Standalone:
 *
 * <pre>{@code
 * // Create Lettuce RedisClient
 * RedisClient redisClient = RedisClient.create("redis://localhost:6379");
 *
 * // Build RedisAgentStateStore
 * AgentStateStore stateStore = RedisAgentStateStore.builder()
 *     .lettuceClient(redisClient)
 *     .build();
 * }</pre>
 *
 * <p>Lettuce Cluster:
 *
 * <pre>{@code
 * // Create Lettuce RedisClusterClient for cluster mode
 * RedisClusterClient clusterClient = RedisClusterClient.create(
 *     RedisURI.create("localhost", 7000));
 *
 * // Build RedisAgentStateStore
 * AgentStateStore stateStore = RedisAgentStateStore.builder()
 *     .lettuceClusterClient(clusterClient)
 *     .build();
 * }</pre>
 *
 * <p>Lettuce Sentinel:
 *
 * <pre>{@code
 * // Create Lettuce RedisClient for sentinel
 * RedisURI sentinelUri = RedisURI.builder()
 *     .withSentinelMasterId("mymaster")
 *     .withSentinel("localhost", 26379)
 *     .withSentinel("localhost", 26380)
 *     .build();
 * RedisClient redisClient = RedisClient.create(sentinelUri);
 *
 * // Build RedisAgentStateStore
 * AgentStateStore stateStore = RedisAgentStateStore.builder()
 *     .lettuceClient(redisClient)
 *     .build();
 * }</pre>
 *
 * <p><strong>Redisson Usage Example:</strong></p>
 *
 * <pre>{@code
 * // Create RedissonClient (configure as needed for your deployment mode)
 * Config config = new Config();
 * config.useSingleServer().setAddress("redis://localhost:6379");
 * // or for cluster: config.useClusterServers().addNodeAddress("redis://localhost:7000");
 * // or for sentinel: config.useSentinelServers().setMasterName("mymaster").addSentinelAddress("redis://localhost:26379");
 *
 * RedissonClient redissonClient = Redisson.create(config);
 *
 * // Build RedisAgentStateStore
 * AgentStateStore stateStore = RedisAgentStateStore.builder()
 *     .redissonClient(redissonClient)
 *     .build();
 * }</pre>
 *
 * <p><strong>Custom Key Prefix Example:</strong></p>
 *
 * <pre>{@code
 * // Create Redis client
 * RedisClient redisClient = RedisClient.create("redis://localhost:6379");
 *
 * // Build RedisAgentStateStore with custom key prefix
 * AgentStateStore stateStore = RedisAgentStateStore.builder()
 *     .jedisClient(redisClient)
 *     .keyPrefix("myapp:session:")
 *     .build();
 * }</pre>
 */
public class RedisAgentStateStore implements AgentStateStore {

    private static final String DEFAULT_KEY_PREFIX = "agentscope:session:";

    private static final String KEYS_SUFFIX = ":_keys";

    private static final String LIST_SUFFIX = ":list";

    private static final String HASH_SUFFIX = ":_hash";

    /**
     * The session tracking-set name without its leading separator ({@code KEYS_SUFFIX} minus the
     * leading {@code ':'}); a state key equal to this would shadow the marker set key itself.
     */
    private static final String MARKER_KEY_NAME = "_keys";

    /**
     * Atomically persist a list state value.
     *
     * <p>KEYS: list key, list-hash key, session keys set, single-state payload key, single-state
     * version key. ARGV: mode ({@code "rewrite"} or {@code "append"}), expected stored hash,
     * expected stored length, the new content hash, the raw session-set member ({@code key}), the
     * list-form session-set member ({@code key + LIST_SUFFIX}), then the JSON items (all items on
     * rewrite, only the new tail on append). Append mode is guarded on the stored hash and length,
     * so a concurrent writer between the caller's read and this EVAL makes the script return
     * {@code -1} with nothing written. The whole operation is a single EVAL so a save can never
     * leave the list half-written. The single-state payload/version keys are removed so a key that
     * was previously saved as a single value does not leave stale data behind.
     */
    private static final String LIST_SAVE_SCRIPT =
            """
            local listKey  = KEYS[1]
            local hashKey  = KEYS[2]
            local keysKey  = KEYS[3]
            local stateKey = KEYS[4]
            local verKey   = KEYS[5]
            local mode     = ARGV[1]
            if mode == 'append' then
              local curHash = redis.call('GET', hashKey)
              if (curHash or false) ~= ARGV[2] then return -1 end
              if redis.call('LLEN', listKey) ~= tonumber(ARGV[3]) then return -1 end
            else
              redis.call('DEL', listKey)
            end
            redis.call('DEL', stateKey, verKey)
            for i = 7, #ARGV do
              redis.call('RPUSH', listKey, ARGV[i])
            end
            redis.call('SET',  hashKey, ARGV[4])
            redis.call('SREM', keysKey, ARGV[5])
            redis.call('SADD', keysKey, ARGV[6])
            return 1
            """;

    /**
     * Atomically delete a single state entry within a session (both its single-value and list
     * forms).
     *
     * <p>KEYS: session keys set, single-state payload key, single-state version key, list key,
     * list-hash key. ARGV: the raw state-key member and its list-form member. Removes the data
     * keys and the tracking members in one EVAL.
     */
    private static final String PER_KEY_DELETE_SCRIPT =
            """
            redis.call('DEL', KEYS[2], KEYS[3], KEYS[4], KEYS[5])
            redis.call('SREM', KEYS[1], ARGV[1], ARGV[2])
            return 1
            """;

    /**
     * Atomically compare-and-set or unconditionally bump a single-value payload, and clear the
     * list form of the same key (a single value replaces any list form). Local to this class so the
     * shared {@link RedisStateVersionSupport#SAVE_SCRIPT} (used by the per-client stores) is left
     * untouched.
     *
     * <p>KEYS: payload key, version key, session keys set, list key, list-hash key. ARGV: JSON
     * payload, expected version ({@link RedisStateVersionSupport#UNCONDITIONAL} for bump),
     * single-value member, list-form member. The list form is cleared only on success, never on a
     * version conflict. Returns the new version on success, or {@code -1} on conflict.
     */
    private static final String SAVE_VALUE_SCRIPT =
            """
            local function current_version()
              if redis.call('EXISTS', KEYS[2]) == 1 then
                return tonumber(redis.call('GET', KEYS[2]))
              end
              if redis.call('EXISTS', KEYS[1]) == 1 then
                return 0
              end
              return 0
            end

            local function do_write(newVersion)
              redis.call('SET', KEYS[1], ARGV[1])
              redis.call('SET', KEYS[2], newVersion)
              redis.call('SREM', KEYS[3], ARGV[4])
              redis.call('DEL', KEYS[4], KEYS[5])
              redis.call('SADD', KEYS[3], ARGV[3])
              return newVersion
            end

            local expected = tonumber(ARGV[2])
            if expected == -1 then
              local newVersion = 1
              if redis.call('EXISTS', KEYS[2]) == 1 then
                newVersion = tonumber(redis.call('GET', KEYS[2])) + 1
              end
              return do_write(newVersion)
            end

            local current = current_version()
            if current ~= expected then
              return -1
            end

            return do_write(expected + 1)
            """;

    /**
     * Atomically delete a whole session: read its tracking-set members, delete every referenced
     * data key (single-value payload + version, or list + list-hash), then delete the tracking
     * set itself — all in one EVAL. Because {@code save}/{@code saveIfVersion} are also a single
     * atomic EVAL on the same slot, Redis serializes a session-clear against a concurrent save to
     * that session, so neither leaves orphaned data nor a torn tracking set.
     *
     * <p>KEYS: the session tracking set key ({@code <prefix>{user/session}:_keys}), which declares
     * the slot. ARGV: keyPrefix, slotId, list suffix, list-hash suffix, version suffix. Every
     * computed data key shares the session hash tag and therefore the same slot.
     */
    private static final String CLEAR_SESSION_SCRIPT =
            """
            local keysKey = KEYS[1]
            local prefix = ARGV[1]
            local slotId = ARGV[2]
            local listSuffix = ARGV[3]
            local hashSuffix = ARGV[4]
            local verSuffix = ARGV[5]
            local members = redis.call('SMEMBERS', keysKey)
            local toDelete = {}
            local n = 0
            for _, m in ipairs(members) do
              if string.sub(m, -#listSuffix) == listSuffix then
                local baseKey = string.sub(m, 1, #m - #listSuffix)
                n = n + 1; toDelete[n] = prefix .. slotId .. ':' .. baseKey .. listSuffix
                n = n + 1; toDelete[n] = prefix .. slotId .. ':' .. baseKey .. listSuffix .. hashSuffix
              else
                local stateKey = prefix .. slotId .. ':' .. m
                n = n + 1; toDelete[n] = stateKey
                n = n + 1; toDelete[n] = stateKey .. verSuffix
              end
            end
            n = n + 1; toDelete[n] = keysKey
            -- Delete in bounded batches: a single unpack(toDelete) on a very large session would
            -- exceed Lua's C-stack limit for varargs, so we unpack sub-ranges of at most BATCH.
            local BATCH = 500
            for i = 1, n, BATCH do
              redis.call('DEL', unpack(toDelete, i, math.min(i + BATCH - 1, n)))
            end
            return n
            """;

    private static final Logger log = LoggerFactory.getLogger(RedisAgentStateStore.class);

    /** Retry budget for the verified (non-atomic-MGET) versioned read. */
    private static final int MAX_VERIFIED_READ_ATTEMPTS = 3;

    private final RedisClientAdapter client;

    private final String keyPrefix;

    private volatile boolean closed;

    private RedisAgentStateStore(Builder builder) {
        if (builder.client == null) {
            throw new IllegalArgumentException("Redis client cannot be null");
        }
        if (builder.keyPrefix == null || builder.keyPrefix.trim().isEmpty()) {
            throw new IllegalArgumentException("Key prefix cannot be null or empty");
        }
        this.client = builder.client;
        this.keyPrefix = builder.keyPrefix;
        if (!client.supportsAtomicMget()) {
            // One-time signal: without an atomic MGET the versioned read falls back to a
            // verified re-read, which costs extra round trips per getVersioned call.
            log.warn(
                    "RedisClientAdapter {} does not support an atomic MGET; getVersioned will use"
                            + " a verified re-read instead of one MGET. Override"
                            + " mget()/supportsAtomicMget() on the adapter for a truly atomic"
                            + " single-command read.",
                    client.getClass().getName());
        }
        warnOnLegacyStateKeys(client, keyPrefix);
    }

    /**
     * One-time startup check: legacy (pre-hash-tag) keys under our prefix are not readable by
     * this version, and without a migration the failure mode is silent data loss (sessions
     * resume with a blank transcript). Surface them loudly instead. Best-effort: a Redis outage
     * at build time must not break construction.
     */
    private static void warnOnLegacyStateKeys(RedisClientAdapter client, String keyPrefix) {
        try {
            Set<String> legacy =
                    io.agentscope.extensions.redis.RedisKeyLayoutMigration.findLegacyStateKeys(
                            client, keyPrefix);
            if (!legacy.isEmpty()) {
                log.warn(
                        "Detected {} keys under key prefix '{}' in the pre-hash-tag legacy"
                                + " layout; they are NOT readable by this version (sessions would"
                                + " resume empty). Migrate them with"
                                + " RedisKeyLayoutMigration.migrateAgentStateStore(...), or drop"
                                + " them via clearAllSessions(true).",
                        legacy.size(),
                        keyPrefix);
            }
        } catch (Exception e) {
            log.debug("Legacy key-layout detection skipped: {}", e.toString());
        }
    }

    /**
     * Creates a new builder for {@link RedisAgentStateStore}.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public boolean supportsVersioning() {
        return true;
    }

    @Override
    public void save(String userId, String sessionId, String key, State value) {
        validateStateKey(key);
        long v = saveVersioned(userId, sessionId, key, value, UNCONDITIONAL_EXPECTED);
        if (v == -1L) {
            throw new RuntimeException(
                    "Version conflict saving state: "
                            + key
                            + ", session="
                            + slotId(userId, sessionId));
        }
    }

    private static final long UNCONDITIONAL_EXPECTED = Long.MIN_VALUE;

    private long saveVersioned(
            String userId, String sessionId, String key, State value, long expectedVersion) {
        String slotId = slotId(userId, sessionId);
        String redisKey = getStateKey(slotId, key);
        String versionKey = RedisStateVersionSupport.versionKey(redisKey);
        String keysKey = getKeysKey(slotId);
        String listKey = getListKey(slotId, key);
        String listHashKey = listKey + HASH_SUFFIX;
        try {
            String json = JsonUtils.getJsonCodec().toJson(value);
            List<String> keys = List.of(redisKey, versionKey, keysKey, listKey, listHashKey);
            List<String> args =
                    List.of(
                            json,
                            expectedVersion == UNCONDITIONAL_EXPECTED
                                    ? RedisStateVersionSupport.UNCONDITIONAL
                                    : Long.toString(expectedVersion),
                            key,
                            key + LIST_SUFFIX);
            return client.evalScript(SAVE_VALUE_SCRIPT, keys, args);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Failed to save state: " + key + ", session=" + slotId, e);
        }
    }

    @Override
    public <T extends State> VersionedState<T> getVersioned(
            String userId, String sessionId, String key, Class<T> type) {
        validateStateKey(key);
        String slotId = slotId(userId, sessionId);
        String redisKey = getStateKey(slotId, key);
        String versionKey = RedisStateVersionSupport.versionKey(redisKey);
        try {
            // Read payload and version as one consistent pair so a concurrent writer cannot
            // produce a torn read (payload from one version, version counter from another).
            List<String> both = readPayloadAndVersion(redisKey, versionKey);
            String json = both.get(0);
            if (json == null) {
                return new VersionedState<>(null, 0L);
            }
            long version = RedisStateVersionSupport.parseVersion(json, both.get(1));
            return new VersionedState<>(JsonUtils.getJsonCodec().fromJson(json, type), version);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to get versioned state: " + key + ", session=" + slotId, e);
        }
    }

    /**
     * Read the payload and version counter as one consistent pair. With an atomic-MGET adapter
     * this is a single MGET. With any other adapter it falls back to a verified re-read: the
     * payload is read again after the version counter, and the pair is accepted only when both
     * payload reads agree.
     *
     * <p>The fallback is sound because every writer of this store updates (payload, version) in a
     * single atomic EVAL: such a write lands either entirely inside or entirely outside the
     * bracket of the two payload reads, so two equal payload reads mean either the pair reflects
     * one consistent state, or a later CAS write will reject the version we read — no torn read
     * can propagate to a caller. Persistent disagreement means heavy write contention and fails
     * loudly after {@link #MAX_VERIFIED_READ_ATTEMPTS} attempts rather than returning corrupt
     * data.
     */
    private List<String> readPayloadAndVersion(String redisKey, String versionKey) {
        if (client.supportsAtomicMget()) {
            return client.mget(redisKey, versionKey);
        }
        for (int attempt = 1; ; attempt++) {
            String payloadBefore = client.get(redisKey);
            String version = client.get(versionKey);
            String payloadAfter = client.get(redisKey);
            if (Objects.equals(payloadBefore, payloadAfter)) {
                return Arrays.asList(payloadBefore, version);
            }
            if (attempt >= MAX_VERIFIED_READ_ATTEMPTS) {
                throw new IllegalStateException(
                        "Could not obtain a consistent versioned read of "
                                + redisKey
                                + " after "
                                + MAX_VERIFIED_READ_ATTEMPTS
                                + " attempts (concurrent writes); retry the operation");
            }
        }
    }

    @Override
    public long saveIfVersion(
            String userId, String sessionId, String key, State value, long expectedVersion) {
        validateStateKey(key);
        if (expectedVersion == UNVERSIONED) {
            // saveVersioned returns the new version directly — no read-back needed (and reading
            // back via State.class is impossible because `State` is a marker interface Jackson
            // cannot instantiate).
            return saveVersioned(userId, sessionId, key, value, UNCONDITIONAL_EXPECTED);
        }
        long result = saveVersioned(userId, sessionId, key, value, expectedVersion);
        return result == -1L ? UNVERSIONED : result;
    }

    @Override
    public void save(String userId, String sessionId, String key, List<? extends State> values) {
        validateStateKey(key);
        String slotId = slotId(userId, sessionId);
        String listKey = getListKey(slotId, key);
        String hashKey = listKey + HASH_SUFFIX;
        String keysKey = getKeysKey(slotId);
        String stateKey = getStateKey(slotId, key);
        String versionKey = RedisStateVersionSupport.versionKey(stateKey);
        List<String> keys = List.of(listKey, hashKey, keysKey, stateKey, versionKey);
        try {
            // Serialize once and reuse for hashing and for the Lua args: on this hot write path
            // every element would otherwise be serialized 2-3 times per save (full hash, prefix
            // hash, push args).
            List<String> serialized = ListHashUtil.serialize(values);
            // Full content hash (every element, serialized) so a modification at any position is
            // detected.
            String currentHash = ListHashUtil.computeHashOfSerialized(serialized);
            String storedHash = client.get(hashKey);
            long existingCount = client.getListLength(listKey);
            // An empty stored list has no hash yet; treat it as a rewrite so the append guard does
            // not compare against a missing hash key.
            boolean rewrite =
                    existingCount == 0
                            || ListHashUtil.needsFullRewriteSerialized(
                                    serialized, storedHash, (int) existingCount);

            // The mutation is a single Lua EVAL. Append mode is guarded on the stored hash and
            // length, so a concurrent writer between our read and the write makes the script
            // return -1 (nothing is written); we then fall back to an atomic full rewrite
            // (last-writer-wins), which is always consistent.
            long result =
                    client.evalScript(
                            LIST_SAVE_SCRIPT,
                            keys,
                            buildListSaveArgs(
                                    rewrite,
                                    storedHash,
                                    existingCount,
                                    currentHash,
                                    key,
                                    rewrite
                                            ? serialized
                                            : serialized.subList(
                                                    (int) existingCount, serialized.size())));
            if (result == -1L) {
                client.evalScript(
                        LIST_SAVE_SCRIPT,
                        keys,
                        buildListSaveArgs(true, null, 0L, currentHash, key, serialized));
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to save list: " + key + ", session=" + slotId, e);
        }
    }

    /**
     * Build the ARGV for {@link #LIST_SAVE_SCRIPT}: [mode, expectedHash, expectedLen, currentHash,
     * rawMember, listMember, json1..jsonN]. {@code expectedHash}/{@code expectedLen} are only used
     * by append mode's guard; for rewrite they are ignored.
     */
    private static List<String> buildListSaveArgs(
            boolean rewrite,
            String expectedHash,
            long expectedLen,
            String currentHash,
            String key,
            List<String> serializedToPush) {
        List<String> args = new ArrayList<>(serializedToPush.size() + 6);
        args.add(rewrite ? "rewrite" : "append");
        args.add(expectedHash == null ? "" : expectedHash);
        args.add(Long.toString(expectedLen));
        args.add(currentHash);
        args.add(key);
        args.add(key + LIST_SUFFIX);
        args.addAll(serializedToPush);
        return args;
    }

    @Override
    public <T extends State> Optional<T> get(
            String userId, String sessionId, String key, Class<T> type) {
        validateStateKey(key);
        String slotId = slotId(userId, sessionId);
        String redisKey = getStateKey(slotId, key);
        try {
            String json = client.get(redisKey);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(JsonUtils.getJsonCodec().fromJson(json, type));
        } catch (Exception e) {
            throw new RuntimeException("Failed to get state: " + key + ", session=" + slotId, e);
        }
    }

    @Override
    public <T extends State> List<T> getList(
            String userId, String sessionId, String key, Class<T> itemType) {
        validateStateKey(key);
        String slotId = slotId(userId, sessionId);
        String redisKey = getListKey(slotId, key);
        try {
            List<String> jsonList = client.rangeList(redisKey, 0, -1);
            if (jsonList == null || jsonList.isEmpty()) {
                return List.of();
            }
            List<T> result = new ArrayList<>();
            for (String json : jsonList) {
                T item = JsonUtils.getJsonCodec().fromJson(json, itemType);
                result.add(item);
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to get list: " + key + ", session=" + slotId, e);
        }
    }

    @Override
    public boolean exists(String userId, String sessionId) {
        String slotId = slotId(userId, sessionId);
        String keysKey = getKeysKey(slotId);
        try {
            // A session exists iff its tracking set has at least one member.
            return client.getSetSize(keysKey) > 0;
        } catch (Exception e) {
            throw new RuntimeException("Failed to check session existence: " + slotId, e);
        }
    }

    @Override
    public void delete(String userId, String sessionId) {
        String slotId = slotId(userId, sessionId);
        String keysKey = getKeysKey(slotId);
        try {
            // Single atomic EVAL: read tracking members + delete all referenced data keys + the
            // marker set. Serialized against a concurrent save (also a single same-slot EVAL), so
            // neither leaves orphans nor a torn tracking set.
            client.evalScript(
                    CLEAR_SESSION_SCRIPT,
                    List.of(keysKey),
                    List.of(
                            keyPrefix,
                            slotId,
                            LIST_SUFFIX,
                            HASH_SUFFIX,
                            RedisStateVersionSupport.VERSION_SUFFIX));
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete session: " + slotId, e);
        }
    }

    @Override
    public void delete(String userId, String sessionId, String key) {
        validateStateKey(key);
        String slotId = slotId(userId, sessionId);
        String keysKey = getKeysKey(slotId);
        String stateKey = getStateKey(slotId, key);
        String versionKey = RedisStateVersionSupport.versionKey(stateKey);
        String listKey = getListKey(slotId, key);
        String hashKey = listKey + HASH_SUFFIX;
        try {
            // Single EVAL: remove the data keys (single-value payload/version and list/list-hash)
            // and the tracking members (both raw and list forms). Without this override the
            // interface default is a silent no-op.
            client.evalScript(
                    PER_KEY_DELETE_SCRIPT,
                    List.of(keysKey, stateKey, versionKey, listKey, hashKey),
                    List.of(key, key + LIST_SUFFIX));
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to delete state key: " + key + ", session=" + slotId, e);
        }
    }

    @Override
    public Set<String> listSessionIds(String userId) {
        String userSegment = normalizeUser(userId);
        // A userId with the user/session separator or glob metacharacters would skew the SCAN
        // MATCH pattern below (see validateUserSegment). Fail fast instead of returning a wrong
        // set.
        validateUserSegment(userSegment);
        try {
            // Keys have the form: {prefix}{{userSegment}/{sessionId}}:_keys.
            // userSegment is guaranteed free of glob metacharacters by the validateUserSegment
            // check above, so the SCAN MATCH pattern is built from it literally.
            String pattern = keyPrefix + "{" + userSegment + "/*}" + KEYS_SUFFIX;
            Set<String> keysKeys = client.findKeysByPattern(pattern);
            Set<String> sessionIds = new HashSet<>();
            String openTag = keyPrefix + "{" + userSegment + "/";
            String closeTag = "}" + KEYS_SUFFIX;
            for (String keysKey : keysKeys) {
                // Strip the prefix and the closing tag to recover the sessionId.
                String afterPrefix = keysKey.substring(openTag.length());
                String sessionId =
                        afterPrefix.substring(0, afterPrefix.length() - closeTag.length());
                sessionIds.add(sessionId);
            }
            return sessionIds;
        } catch (Exception e) {
            throw new RuntimeException("Failed to list sessions", e);
        }
    }

    /** Sentinel for {@code userId == null} (anonymous sessions). */
    private static final String ANON_USER = "__anon__";

    private static String normalizeUser(String userId) {
        return userId == null || userId.isBlank() ? ANON_USER : userId;
    }

    /**
     * Validate the user-id segment that is embedded literally both in the Redis Cluster hash tag
     * {@code {user/session}} and in the {@code listSessionIds} SCAN MATCH pattern. Rejects:
     *
     * <ul>
     *   <li>{@code { } } — would terminate the hash tag early;
     *   <li>{@code /} — is the separator between user and session, so a {@code userId} like
     *       {@code "a/b"} would make {@code listSessionIds("a")} match (and leak) sessions that
     *       actually belong to {@code "a/b"};
     *   <li>{@code * ? [ ] \} — Redis glob metacharacters that would widen or skew the
     *       {@code listSessionIds} SCAN pattern.
     * </ul>
     *
     * <p>Note: {@code ,} needs no rejection — Redis pattern matching ({@code stringmatchlen})
     * supports only {@code *}, {@code ?}, {@code [...]} and {@code \} escapes; there is no
     * {@code {a,b}} alternation, so a comma is always literal.
     */
    private static void validateUserSegment(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '{' || c == '}' || c == '/' || c == '*' || c == '?' || c == '[' || c == ']'
                    || c == '\\') {
                throw new IllegalArgumentException(
                        "userId must not contain any of the reserved characters"
                                + " { } / * ? [ ] \\ (used for Redis Cluster hash tags, the"
                                + " user/session separator, and SCAN glob patterns)");
            }
        }
    }

    /**
     * Validate the session-id segment that is embedded inside the Redis Cluster hash tag
     * {@code {user/session}}. Only {@code { }} are rejected (they would terminate the tag early
     * and break key extraction).
     *
     * <p>Everything else round-trips safely: the session id never appears literally in the
     * {@code listSessionIds} MATCH pattern (the pattern matches that position with {@code *}), and
     * {@code listSessionIds} recovers it by stripping the fixed {@code <prefix>{<user>/} prefix
     * and {@code }:_keys} tail — so composite ids such as {@code "agent1/run42"} or ids containing
     * {@code ,} are valid and are returned intact.
     */
    private static void validateSessionIdSegment(String sessionId) {
        if (sessionId.indexOf('{') >= 0 || sessionId.indexOf('}') >= 0) {
            throw new IllegalArgumentException(
                    "sessionId must not contain '{' or '}' (reserved for Redis Cluster hash tags)");
        }
    }

    /**
     * Validate a state key. Rejects blank keys, braces (reserved for the Redis Cluster hash tag),
     * the reserved list suffix (so a single-value key can never collide with the list-form
     * tracking member of another key, which would corrupt session deletion), and the remaining
     * companion-key names: the version-counter suffix and the list-hash suffix (such a key would
     * shadow that companion of another entry and corrupt its version CAS / change detection), and
     * the exact session marker name (which would shadow the tracking set itself).
     */
    private static void validateStateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("state key must not be blank");
        }
        if (key.indexOf('{') >= 0 || key.indexOf('}') >= 0) {
            throw new IllegalArgumentException(
                    "state key must not contain '{' or '}' (reserved for Redis Cluster hash tags)");
        }
        if (key.endsWith(LIST_SUFFIX)) {
            throw new IllegalArgumentException(
                    "state key must not end with reserved suffix '" + LIST_SUFFIX + "'");
        }
        // Same collision class as the :list guard above: these names would shadow the version
        // counter, list hash or session tracking set of this or another entry, corrupting that
        // entry's version CAS / change detection (or failing loudly with WRONGTYPE).
        if (key.endsWith(RedisStateVersionSupport.VERSION_SUFFIX)
                || key.endsWith(LIST_SUFFIX + HASH_SUFFIX)
                || MARKER_KEY_NAME.equals(key)) {
            throw new IllegalArgumentException(
                    "state key '"
                            + key
                            + "' uses a reserved companion-key name: suffix '"
                            + RedisStateVersionSupport.VERSION_SUFFIX
                            + "' or '"
                            + LIST_SUFFIX
                            + HASH_SUFFIX
                            + "', or the exact name '"
                            + MARKER_KEY_NAME
                            + "' (it would shadow another entry's version counter, list hash or"
                            + " the session tracking set)");
        }
    }

    /**
     * Combine {@code (userId, sessionId)} into a single Redis slot identifier.
     *
     * <p>The result is wrapped in a Redis Cluster hash tag {@code {...}} so that all keys derived
     * from this slot (payload, version, keys-set, list, list-hash) hash to the same slot. This is
     * required by the multi-key {@code SAVE_SCRIPT} Lua eval in cluster mode. The
     * {@code userId} segment may not contain {@code { } / * ? [ ] \\} (see
     * {@link #validateUserSegment}); the {@code sessionId} segment may not contain {@code { }}
     * (see {@link #validateSessionIdSegment}) — composite ids containing {@code /} or {@code ,}
     * are valid and round-trip through {@link #listSessionIds}.
     */
    private static String slotId(String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        validateSessionIdSegment(sessionId);
        // A real userId equal to the anonymous sentinel would share the anonymous slot and silently
        // mix data with unauthenticated sessions; reject it (anonymous sessions still use it).
        if (userId != null && !userId.isBlank() && ANON_USER.equals(userId)) {
            throw new IllegalArgumentException(
                    "userId must not equal the reserved anonymous-user sentinel '"
                            + ANON_USER
                            + "'");
        }
        String user = normalizeUser(userId);
        validateUserSegment(user);
        return "{" + user + "/" + sessionId + "}";
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        client.close();
    }

    /**
     * Clear all sessions stored in Redis (marker-driven only).
     *
     * <p><strong>Destructive — for testing / internal cleanup only.</strong> Only session marker
     * sets ({@code <prefix>{user/session}:_keys}) found by the scan are removed, together with the
     * data keys they track. Each session is cleared with a single atomic EVAL
     * ({@link #CLEAR_SESSION_SCRIPT}), so a concurrent {@code save} to a session being cleared is
     * serialized by Redis — neither leaves orphaned data nor a torn tracking set.
     *
     * <p><em>Not</em> removed by this call: keys no live {@code _keys} set tracks — orphans from
     * a partial write, payload keys whose marker was deleted out of band, and keys in the
     * pre-hash-tag legacy layout (which the marker pass cannot address). Use
     * {@link #clearAllSessions(boolean)} with {@code includeUntracked=true} to sweep those too.
     *
     * <p><em>Best-effort across sessions:</em> the marker scan is a snapshot; sessions created
     * during the scan are not cleared. Run while the system is quiescent, or re-run, to be sure.
     *
     * @return Mono that completes with the number of Redis keys actually deleted
     */
    public Mono<Integer> clearAllSessions() {
        return clearAllSessions(false);
    }

    /**
     * Clear all sessions stored in Redis, optionally sweeping untracked keys as well.
     *
     * <p>The marker-driven pass runs first (see {@link #clearAllSessions()}). When
     * {@code includeUntracked} is true, a full {@code SCAN keyPrefix*} sweep then deletes
     * <em>every</em> remaining key under the key prefix: orphans from partial writes, keys whose
     * {@code _keys} marker was removed out of band, and pre-hash-tag legacy-layout keys. Keys are
     * deleted one by one so the sweep is safe in cluster mode (a multi-key {@code DEL} spanning
     * slots would fail with {@code CROSSSLOT}); it is not atomic against concurrent writers — run
     * it while the system is quiescent.
     *
     * @param includeUntracked true to also delete keys not reachable from any live {@code _keys}
     *     set
     * @return Mono that completes with the number of Redis keys deleted (the sweep counts every
     *     key it issues a delete for)
     */
    public Mono<Integer> clearAllSessions(boolean includeUntracked) {
        return Mono.fromSupplier(
                        () -> {
                            try {
                                int deleted = clearTrackedSessions();
                                if (includeUntracked) {
                                    Set<String> allKeys = client.findKeysByPattern(keyPrefix + "*");
                                    for (String key : allKeys) {
                                        client.deleteKeys(key);
                                        deleted++;
                                    }
                                }
                                return deleted;
                            } catch (Exception e) {
                                throw new RuntimeException("Failed to clear sessions", e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Marker-driven pass: atomically clear every session reachable from a live {@code _keys}
     * tracking set, one EVAL per session.
     *
     * @return the number of Redis keys deleted
     */
    private int clearTrackedSessions() {
        Set<String> markerKeys = client.findKeysByPattern(keyPrefix + "*" + KEYS_SUFFIX);
        int deleted = 0;
        for (String marker : markerKeys) {
            String slotId =
                    marker.substring(keyPrefix.length(), marker.length() - KEYS_SUFFIX.length());
            long n =
                    client.evalScript(
                            CLEAR_SESSION_SCRIPT,
                            List.of(marker),
                            List.of(
                                    keyPrefix,
                                    slotId,
                                    LIST_SUFFIX,
                                    HASH_SUFFIX,
                                    RedisStateVersionSupport.VERSION_SUFFIX));
            deleted += (int) n;
        }
        return deleted;
    }

    /**
     * Get the Redis key for a single state value.
     *
     * @param sessionId the session ID
     * @param key the state key
     * @return Redis key in format {prefix}{sessionId}:{key}
     */
    private String getStateKey(String sessionId, String key) {
        return keyPrefix + sessionId + ":" + key;
    }

    /**
     * Get the Redis key for a list state value.
     *
     * @param sessionId the session ID
     * @param key the state key
     * @return Redis key in format {prefix}{sessionId}:{key}:list
     */
    private String getListKey(String sessionId, String key) {
        return keyPrefix + sessionId + ":" + key + LIST_SUFFIX;
    }

    /**
     * Get the Redis key for tracking session keys.
     *
     * @param sessionId the session ID
     * @return Redis key in format {prefix}{sessionId}:_keys
     */
    private String getKeysKey(String sessionId) {
        return keyPrefix + sessionId + KEYS_SUFFIX;
    }

    /**
     * Builder for {@link RedisAgentStateStore}.
     *
     * <p>The builder supports multiple Redis client types. Only one client type should be set.
     *
     * <p>Supported client types:
     * <ul>
     *   <li>Jedis: {@link #jedisClient(UnifiedJedis)}
     *   <li>Lettuce Standalone/Sentinel: {@link #lettuceClient(RedisClient)}
     *   <li>Lettuce Cluster: {@link #lettuceClusterClient(RedisClusterClient)}
     *   <li>Redisson: {@link #redissonClient(RedissonClient)}
     *   <li>Custom: {@link #clientAdapter(RedisClientAdapter)}
     * </ul>
     */
    public static class Builder {

        private String keyPrefix = DEFAULT_KEY_PREFIX;

        private RedisClientAdapter client;

        private void assertClientNotSet(String newType) {
            if (this.client != null) {
                throw new IllegalStateException(
                        "A Redis client is already configured; only one client type is allowed"
                                + " (attempted: "
                                + newType
                                + ")");
            }
        }

        public Builder keyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
            return this;
        }

        public Builder jedisClient(UnifiedJedis unifiedJedis) {
            assertClientNotSet("jedis");
            this.client = JedisClientAdapter.of(unifiedJedis);
            return this;
        }

        public Builder lettuceClient(RedisClient redisClient) {
            assertClientNotSet("lettuce");
            this.client = LettuceClientAdapter.of(redisClient);
            return this;
        }

        public Builder lettuceClusterClient(RedisClusterClient redisClusterClient) {
            assertClientNotSet("lettuceCluster");
            this.client = LettuceClientAdapter.of(redisClusterClient);
            return this;
        }

        public Builder redissonClient(RedissonClient redissonClient) {
            assertClientNotSet("redisson");
            this.client = RedissonClientAdapter.of(redissonClient);
            return this;
        }

        public Builder clientAdapter(RedisClientAdapter clientAdapter) {
            assertClientNotSet("custom");
            this.client = clientAdapter;
            return this;
        }

        public RedisAgentStateStore build() {
            return new RedisAgentStateStore(this);
        }
    }
}
