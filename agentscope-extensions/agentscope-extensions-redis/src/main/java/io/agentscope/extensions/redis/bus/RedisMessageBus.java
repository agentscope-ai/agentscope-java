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
package io.agentscope.extensions.redis.bus;

import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.bus.BusEntry;
import io.agentscope.harness.agent.bus.MessageBus;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.params.XAddParams;
import redis.clients.jedis.resps.StreamEntry;

/**
 * Redis-backed {@link MessageBus} implementation.
 *
 * <p>Provides true cross-node message delivery using Redis as the shared transport:
 * <ul>
 *   <li><b>Mode A (drain queue)</b> — Redis List with {@code LPUSH}/{@code RPOP}. A Lua
 *       script atomically pops up to N entries in a single round-trip.</li>
 *   <li><b>Mode C (replay log)</b> — Redis Stream with {@code XADD}/{@code XRANGE}. The
 *       {@code MAXLEN ~} option caps the stream length; entry IDs serve as cursors.</li>
 *   <li><b>Mode D (pub/sub)</b> — Redis Pub/Sub with {@code PUBLISH}/{@code SUBSCRIBE}.
 *       Subscriptions run on a bounded-elastic scheduler thread and are cleaned up on
 *       Flux cancellation or {@link #close()}.</li>
 * </ul>
 *
 * <h2>Key layout</h2>
 * <pre>
 * {prefix}queue:{keyHash}   — Redis List (Mode A entries)
 * {prefix}seq:{keyHash}     — Redis counter for Mode A entry IDs
 * {prefix}stream:{keyHash}  — Redis Stream (Mode C entries)
 * {prefix}channel:{keyHash} — Redis Pub/Sub channel (Mode D)
 * </pre>
 *
 * <p>The {@code {keyHash}} segments are real Redis-Cluster
 * <a href="https://redis.io/docs/reference/cluster-spec/#hash-tags">hash tags</a>, not
 * just visual placeholders: the braces force all four kinds of keys for a given caller-supplied
 * key to land in the same cluster slot. This is required because the Mode-A push LUA script
 * (see {@code QUEUE_PUSH_SCRIPT}) touches both the seq counter and the queue list in one
 * server-side step — without a shared hash tag, those keys would land in different slots and
 * the script would fail with {@code CROSSSLOT} on a {@code JedisCluster} client.
 *
 * <h2>Requirements</h2>
 * Requires Redis ≥ 6.2 (uses {@code XRANGE} exclusive-id syntax {@code (id} for Mode C cursors).
 *
 * <h2>Subscribe semantics</h2>
 * {@link #subscribe} relies on Redis Pub/Sub, so messages published before the SUBSCRIBE round-trip
 * has completed will be lost — this matches the {@link MessageBus} Mode D contract ("only
 * currently-subscribed listeners receive a payload"). Callers that need at-least-once delivery
 * should use Mode C (replay log) instead.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * UnifiedJedis jedis = new JedisPooled("redis://localhost:6379");
 * RedisMessageBus bus = new RedisMessageBus(jedis);
 *
 * // Or via RedisDistributedStore (auto-wired):
 * RedisDistributedStore store = RedisDistributedStore.fromJedis(jedis);
 * // store.messageBus() returns a RedisMessageBus
 * }</pre>
 */
public class RedisMessageBus implements MessageBus {

    private static final Logger log = LoggerFactory.getLogger(RedisMessageBus.class);

    /** Default Redis key prefix. */
    public static final String DEFAULT_KEY_PREFIX = "agentscope:bus:";

    /**
     * Lua script: atomically RPOP up to N entries from a list.
     * Returns an array of string values (or empty array if the list is empty).
     */
    private static final String BATCH_RPOP_SCRIPT =
            "local key = KEYS[1] "
                    + "local maxCount = tonumber(ARGV[1]) "
                    + "local results = {} "
                    + "for i = 1, maxCount do "
                    + "  local val = redis.call('RPOP', key) "
                    + "  if not val then break end "
                    + "  table.insert(results, val) "
                    + "end "
                    + "return results";

    /**
     * Lua script: atomically assign an entry id (INCR on seq key), wrap the caller-supplied payload
     * JSON in an envelope, and LPUSH it onto the queue. Returns the formatted entry id.
     *
     * <p>Combining INCR + LPUSH into one server-side step is essential: doing them as two separate
     * round-trips lets concurrent pushers' entry ids reorder relative to their LPUSH order, so the
     * id embedded in the envelope no longer matches the queue position.
     */
    private static final String QUEUE_PUSH_SCRIPT =
            "local seqKey = KEYS[1] "
                    + "local queueKey = KEYS[2] "
                    + "local payloadJson = ARGV[1] "
                    + "local id = redis.call('INCR', seqKey) "
                    + "local idStr = string.format('%020d', id) "
                    + "local envelope = '{\"_id\":\"' .. idStr "
                    + "  .. '\",\"payload\":' .. payloadJson .. '}' "
                    + "redis.call('LPUSH', queueKey, envelope) "
                    + "return idStr";

    private final UnifiedJedis jedis;
    private final String keyPrefix;

    /**
     * Tracks live Pub/Sub subscriptions so {@link #close()} can tear them down. Multiple
     * subscribers may target the same channel; a set keyed on the {@code JedisPubSub} identity
     * keeps each one tracked independently.
     */
    private final Set<JedisPubSub> activeSubscriptions = ConcurrentHashMap.newKeySet();

    /**
     * Creates a Redis message bus with the default key prefix.
     *
     * @param jedis initialized Jedis client; must not be {@code null}
     */
    public RedisMessageBus(UnifiedJedis jedis) {
        this(jedis, DEFAULT_KEY_PREFIX);
    }

    /**
     * Creates a Redis message bus with a custom key prefix.
     *
     * @param jedis     initialized Jedis client; must not be {@code null}
     * @param keyPrefix prefix for all Redis keys; trailing {@code ":"} is added if absent.
     *                  If blank, the {@linkplain #DEFAULT_KEY_PREFIX default} is used.
     */
    public RedisMessageBus(UnifiedJedis jedis, String keyPrefix) {
        this.jedis = Objects.requireNonNull(jedis, "jedis must not be null");
        this.keyPrefix = normalizePrefix(keyPrefix);
    }

    // ---- Mode A: drain queue ----

    @Override
    public Mono<String> queuePush(String key, Map<String, Object> payload) {
        return Mono.fromCallable(
                () -> {
                    String payloadJson =
                            JsonUtils.getJsonCodec().toJson(payload != null ? payload : Map.of());
                    Object result =
                            jedis.eval(
                                    QUEUE_PUSH_SCRIPT,
                                    List.of(seqKey(key), queueKey(key)),
                                    List.of(payloadJson));
                    return bytesToString(result);
                });
    }

    @Override
    public Mono<List<BusEntry>> queueDrain(String key, int maxCount) {
        return Mono.fromCallable(
                () -> {
                    String qKey = queueKey(key);
                    Object result =
                            jedis.eval(
                                    BATCH_RPOP_SCRIPT,
                                    List.of(qKey),
                                    List.of(String.valueOf(maxCount)));

                    List<BusEntry> entries = new ArrayList<>();
                    if (!(result instanceof List<?> rawList)) {
                        return entries;
                    }
                    for (Object item : rawList) {
                        String json = bytesToString(item);
                        if (json == null || json.isEmpty()) {
                            continue;
                        }
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> envelope =
                                    JsonUtils.getJsonCodec().fromJson(json, Map.class);
                            String entryId =
                                    envelope.get("_id") != null
                                            ? envelope.get("_id").toString()
                                            : "";
                            @SuppressWarnings("unchecked")
                            Map<String, Object> payload =
                                    (Map<String, Object>) envelope.get("payload");
                            entries.add(
                                    new BusEntry(entryId, payload != null ? payload : Map.of()));
                        } catch (Exception e) {
                            log.warn(
                                    "queueDrain: failed to parse entry, skipping: {}",
                                    e.getMessage());
                        }
                    }
                    return entries;
                });
    }

    @Override
    public Mono<Void> queueDelete(String key) {
        // Only drop the list — keep the seq counter so future pushes never recycle entry ids that
        // earlier consumers may still reference. This mirrors WorkspaceMessageBus, whose AtomicLong
        // is process-global and survives directory deletion.
        return Mono.fromRunnable(() -> jedis.del(queueKey(key)));
    }

    @Override
    public Mono<Boolean> queuePeek(String key) {
        return Mono.fromCallable(() -> jedis.llen(queueKey(key)) > 0);
    }

    // ---- Mode C: replay log ----

    @Override
    public Mono<String> logAppend(String key, Map<String, Object> payload, int maxLen) {
        return Mono.fromCallable(
                () -> {
                    String streamKey = streamKey(key);
                    String json = JsonUtils.getJsonCodec().toJson(payload);

                    XAddParams params = XAddParams.xAddParams();
                    if (maxLen > 0) {
                        params.maxLen(maxLen).approximateTrimming();
                    }

                    var entryId = jedis.xadd(streamKey, params, Map.of("data", json));
                    return entryId != null ? entryId.toString() : "";
                });
    }

    @Override
    public Mono<List<BusEntry>> logRead(String key, String since, int maxCount) {
        return Mono.fromCallable(
                () -> {
                    String streamKey = streamKey(key);
                    String start = (since != null && !since.isEmpty()) ? "(" + since : "-";
                    List<StreamEntry> entries = jedis.xrange(streamKey, start, "+", maxCount);

                    List<BusEntry> result = new ArrayList<>();
                    if (entries == null) {
                        return result;
                    }
                    for (StreamEntry entry : entries) {
                        Map<String, String> fields = entry.getFields();
                        String json = fields != null ? fields.get("data") : null;
                        if (json == null || json.isEmpty()) {
                            continue;
                        }
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> payload =
                                    JsonUtils.getJsonCodec().fromJson(json, Map.class);
                            result.add(new BusEntry(entry.getID().toString(), payload));
                        } catch (Exception e) {
                            log.warn(
                                    "logRead: failed to parse entry, skipping: {}", e.getMessage());
                        }
                    }
                    return result;
                });
    }

    @Override
    public Mono<Void> logTrim(String key) {
        return Mono.fromRunnable(() -> jedis.del(streamKey(key)));
    }

    // ---- Mode D: pub/sub ----

    @Override
    public Mono<Void> publish(String key, Map<String, Object> payload) {
        return Mono.fromRunnable(
                () -> {
                    String channel = channelKey(key);
                    String json = JsonUtils.getJsonCodec().toJson(payload);
                    jedis.publish(channel, json);
                });
    }

    @Override
    public Flux<Map<String, Object>> subscribe(String key) {
        final String channel = channelKey(key);
        return Flux.<Map<String, Object>>create(
                        sink -> {
                            // Set when the Flux is cancelled. Lets onSubscribe (which may run
                            // AFTER cancel in the race below) honor the cancel by unsubscribing
                            // as soon as the connection actually exists.
                            //
                            // The race we are fixing: if cancel fires before jedis.subscribe()
                            // has handed control to JedisPubSub, pubSub.isSubscribed() is still
                            // false at cancel time, so the onCancel branch below skips
                            // unsubscribe and the subscription proceeds anyway — leaking a Jedis
                            // pool connection that nothing else can ever close.
                            AtomicBoolean cancelled = new AtomicBoolean(false);
                            JedisPubSub pubSub =
                                    new JedisPubSub() {
                                        @Override
                                        public void onMessage(String channel, String message) {
                                            try {
                                                @SuppressWarnings("unchecked")
                                                Map<String, Object> payload =
                                                        JsonUtils.getJsonCodec()
                                                                .fromJson(message, Map.class);
                                                if (payload != null) {
                                                    sink.next(payload);
                                                }
                                            } catch (Exception e) {
                                                log.warn(
                                                        "subscribe: failed to parse"
                                                                + " message on {}: {}",
                                                        channel,
                                                        e.getMessage());
                                            }
                                        }

                                        @Override
                                        public void onSubscribe(
                                                String channel, int subscribedChannels) {
                                            log.debug("Subscribed to channel: {}", channel);
                                            if (cancelled.get()) {
                                                try {
                                                    unsubscribe();
                                                } catch (Exception e) {
                                                    log.debug(
                                                            "Late-cancel unsubscribe from {}"
                                                                    + " failed: {}",
                                                            channel,
                                                            e.getMessage());
                                                }
                                            }
                                        }

                                        @Override
                                        public void onUnsubscribe(
                                                String channel, int subscribedChannels) {
                                            log.debug("Unsubscribed from channel: {}", channel);
                                        }
                                    };

                            activeSubscriptions.add(pubSub);

                            sink.onCancel(
                                    () -> {
                                        cancelled.set(true);
                                        try {
                                            if (pubSub.isSubscribed()) {
                                                pubSub.unsubscribe();
                                            }
                                        } catch (Exception e) {
                                            log.debug(
                                                    "Error unsubscribing from {}: {}",
                                                    channel,
                                                    e.getMessage());
                                        }
                                        activeSubscriptions.remove(pubSub);
                                    });

                            try {
                                // Blocks the current thread until unsubscribed
                                jedis.subscribe(pubSub, channel);
                            } finally {
                                // If subscribe returns by exception or by remote close (not via
                                // the onCancel path), make sure we don't leak the entry.
                                activeSubscriptions.remove(pubSub);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    // ---- Lifecycle ----

    @Override
    public void close() {
        for (JedisPubSub pubSub : activeSubscriptions) {
            try {
                if (pubSub.isSubscribed()) {
                    pubSub.unsubscribe();
                }
            } catch (Exception e) {
                log.debug("Error closing subscription: {}", e.getMessage());
            }
        }
        activeSubscriptions.clear();
    }

    // ---- Key helpers ----

    private String queueKey(String key) {
        return keyPrefix + "queue:{" + hashKey(key) + "}";
    }

    private String seqKey(String key) {
        return keyPrefix + "seq:{" + hashKey(key) + "}";
    }

    private String streamKey(String key) {
        return keyPrefix + "stream:{" + hashKey(key) + "}";
    }

    private String channelKey(String key) {
        return keyPrefix + "channel:{" + hashKey(key) + "}";
    }

    /** Sanitises the caller-supplied key into a Redis-safe suffix with a hash for uniqueness. */
    static String hashKey(String key) {
        int hash = key.hashCode() & 0x7fffffff;
        return key.replaceAll("[^a-zA-Z0-9._-]", "_") + "_" + Integer.toHexString(hash);
    }

    /**
     * EVAL / RPOP can surface results as {@code String} (JedisPooled) or {@code byte[]}
     * (JedisCluster / certain pipeline modes). Normalize to UTF-8 string here so callers don't
     * have to type-switch.
     */
    private static String bytesToString(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return obj.toString();
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return DEFAULT_KEY_PREFIX;
        }
        return prefix.endsWith(":") ? prefix : prefix + ":";
    }
}
