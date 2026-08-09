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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.redisson.api.RedissonClient;
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
 * must be migrated.
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
        long v = saveVersioned(userId, sessionId, key, value, true, 0L);
        if (v == -1L) {
            throw new RuntimeException(
                    "Version conflict saving state: "
                            + key
                            + ", session="
                            + slotId(userId, sessionId));
        }
    }

    private long saveVersioned(
            String userId,
            String sessionId,
            String key,
            State value,
            boolean unconditional,
            long expectedVersion) {
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
                            unconditional
                                    ? RedisStateVersionSupport.UNCONDITIONAL
                                    : Long.toString(expectedVersion),
                            key,
                            key + LIST_SUFFIX);
            return client.evalScript(SAVE_VALUE_SCRIPT, keys, args);
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
            // Read payload and version in a single atomic MGET so a concurrent writer cannot
            // produce a torn read (payload from one version, version counter from another).
            List<String> both = client.mget(redisKey, versionKey);
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

    @Override
    public long saveIfVersion(
            String userId, String sessionId, String key, State value, long expectedVersion) {
        validateStateKey(key);
        if (expectedVersion == UNVERSIONED) {
            save(userId, sessionId, key, value);
            return getVersioned(userId, sessionId, key, State.class).version();
        }
        long result = saveVersioned(userId, sessionId, key, value, false, expectedVersion);
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
            // Full content hash (every element, serialized) so a modification at any position is
            // detected.
            String currentHash = ListHashUtil.computeHash(values);
            String storedHash = client.get(hashKey);
            long existingCount = client.getListLength(listKey);
            // An empty stored list has no hash yet; treat it as a rewrite so the append guard does
            // not compare against a missing hash key.
            boolean rewrite =
                    existingCount == 0
                            || ListHashUtil.needsFullRewrite(
                                    values, storedHash, (int) existingCount);

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
                                            ? values
                                            : values.subList((int) existingCount, values.size())));
            if (result == -1L) {
                client.evalScript(
                        LIST_SAVE_SCRIPT,
                        keys,
                        buildListSaveArgs(true, null, 0L, currentHash, key, values));
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
            List<? extends State> toPush) {
        List<String> args = new ArrayList<>(toPush.size() + 6);
        args.add(rewrite ? "rewrite" : "append");
        args.add(expectedHash == null ? "" : expectedHash);
        args.add(Long.toString(expectedLen));
        args.add(currentHash);
        args.add(key);
        args.add(key + LIST_SUFFIX);
        for (State item : toPush) {
            args.add(JsonUtils.getJsonCodec().toJson(item));
        }
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
        // Same reserved-character rule as slotId: a userId with '/' or ',' would otherwise skew the
        // SCAN MATCH pattern below (see validateScopeId). Fail fast instead of returning a wrong
        // set.
        validateScopeId(userSegment, "userId");
        try {
            // Keys have the form: {prefix}{{userSegment}/{sessionId}}:_keys.
            // userSegment is guaranteed free of glob metacharacters by the validateScopeId
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
     * Validate a user-id or session-id segment that is embedded inside the Redis Cluster hash tag
     * {@code {user/session}}. Rejects:
     *
     * <ul>
     *   <li>{@code { } } — would terminate the hash tag early;
     *   <li>{@code /} — is the separator between user and session, so a {@code userId} like
     *       {@code "a/b"} would make {@code listSessionIds("a")} match (and leak) sessions that
     *       actually belong to {@code "a/b"};
     *   <li>{@code ,} — is the delimiter of a glob set in SCAN/MATCH patterns ({@code {a,b}}), so a
     *       {@code userId} like {@code "a,b"} would make the pattern parse as a glob set and return
     *       nothing;
     *   <li>{@code * ? [ ] \} — Redis glob metacharacters that would widen or skew the
     *       {@code listSessionIds} SCAN pattern.
     * </ul>
     */
    private static void validateScopeId(String value, String name) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '{' || c == '}' || c == '/' || c == ',' || c == '*' || c == '?' || c == '['
                    || c == ']' || c == '\\') {
                throw new IllegalArgumentException(
                        name
                                + " must not contain any of the reserved characters"
                                + " { } / , * ? [ ] \\ (used for Redis Cluster hash tags, the"
                                + " user/session separator, and SCAN glob patterns)");
            }
        }
    }

    /**
     * Validate a state key. Rejects blank keys, braces (reserved for the Redis Cluster hash tag),
     * and keys ending with the reserved list suffix so a single-value key can never collide with
     * the list-form tracking member of another key (which would corrupt session deletion).
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
    }

    /**
     * Combine {@code (userId, sessionId)} into a single Redis slot identifier.
     *
     * <p>The result is wrapped in a Redis Cluster hash tag {@code {...}} so that all keys derived
     * from this slot (payload, version, keys-set, list, list-hash) hash to the same slot. This is
     * required by the multi-key {@code SAVE_SCRIPT} Lua eval in cluster mode. Neither
     * {@code userId} nor {@code sessionId} may contain the reserved characters {@code { } / , * ? [ ] \\}
     * — see {@link #validateScopeId} for why each is rejected.
     */
    private static String slotId(String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        validateScopeId(sessionId, "sessionId");
        // A real userId equal to the anonymous sentinel would share the anonymous slot and silently
        // mix data with unauthenticated sessions; reject it (anonymous sessions still use it).
        if (userId != null && !userId.isBlank() && ANON_USER.equals(userId)) {
            throw new IllegalArgumentException(
                    "userId must not equal the reserved anonymous-user sentinel '"
                            + ANON_USER
                            + "'");
        }
        String user = normalizeUser(userId);
        validateScopeId(user, "userId");
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
     * Clear all sessions stored in Redis.
     *
     * <p><strong>Destructive — for testing / internal cleanup only.</strong> Only session marker
     * sets ({@code <prefix>{user/session}:_keys}) found by the scan are removed, together with the
     * data keys they track. Each session is cleared with a single atomic EVAL
     * ({@link #CLEAR_SESSION_SCRIPT}), so a concurrent {@code save} to a session being cleared is
     * serialized by Redis — neither leaves orphaned data nor a torn tracking set.
     *
     * <p><em>Best-effort across sessions:</em> the marker scan is a snapshot; sessions created
     * during the scan are not cleared. Run while the system is quiescent, or re-run, to be sure.
     *
     * @return Mono that completes with the number of Redis keys actually deleted
     */
    public Mono<Integer> clearAllSessions() {
        return Mono.fromSupplier(
                        () -> {
                            try {
                                Set<String> markerKeys =
                                        client.findKeysByPattern(keyPrefix + "*" + KEYS_SUFFIX);
                                int deleted = 0;
                                for (String marker : markerKeys) {
                                    String slotId =
                                            marker.substring(
                                                    keyPrefix.length(),
                                                    marker.length() - KEYS_SUFFIX.length());
                                    long n =
                                            client.evalScript(
                                                    CLEAR_SESSION_SCRIPT,
                                                    List.of(marker),
                                                    List.of(
                                                            keyPrefix,
                                                            slotId,
                                                            LIST_SUFFIX,
                                                            HASH_SUFFIX,
                                                            RedisStateVersionSupport
                                                                    .VERSION_SUFFIX));
                                    deleted += (int) n;
                                }
                                return deleted;
                            } catch (Exception e) {
                                throw new RuntimeException("Failed to clear sessions", e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
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
