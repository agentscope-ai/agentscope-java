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
package io.agentscope.harness.claw.session.spi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

/**
 * Redis-hash-backed {@link KvStore}. Each key is stored as a single JSON string at {@code
 * keyPrefix + key}. No per-replica cache — every {@code get} round-trips to Redis to guarantee
 * cross-replica freshness. {@link #mutate} uses an in-process per-key mutex (best-effort) — for
 * stronger guarantees, callers should use a real distributed lock or Redis WATCH/MULTI.
 *
 * <p>Trade-off: the cross-replica freshness is the whole point — accept the per-call latency
 * because we're in a multi-tenant distributed deployment, where a stale in-replica cache is
 * actively wrong (user updates a binding on R1, R2 keeps serving the old version).
 */
public class RedisKvStore<V> implements KvStore<V> {

    private final UnifiedJedis jedis;
    private final ObjectMapper mapper;
    private final TypeReference<V> typeRef;
    private final String keyPrefix;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public RedisKvStore(
            UnifiedJedis jedis, ObjectMapper mapper, TypeReference<V> typeRef, String keyPrefix) {
        this.jedis = Objects.requireNonNull(jedis);
        this.mapper = Objects.requireNonNull(mapper);
        this.typeRef = Objects.requireNonNull(typeRef);
        this.keyPrefix = Objects.requireNonNull(keyPrefix);
    }

    @Override
    public Optional<V> get(String key) {
        String json = jedis.get(redisKey(key));
        if (json == null) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(json, typeRef));
        } catch (IOException e) {
            throw new RuntimeException("Failed to deserialise value at " + redisKey(key), e);
        }
    }

    @Override
    public void put(String key, V value) {
        synchronized (lockFor(key)) {
            jedis.set(redisKey(key), serialise(value));
        }
    }

    @Override
    public void remove(String key) {
        synchronized (lockFor(key)) {
            jedis.del(redisKey(key));
        }
    }

    @Override
    public V mutate(String key, V emptyValue, Function<V, V> mutator) {
        synchronized (lockFor(key)) {
            V current = get(key).orElse(emptyValue);
            V next = mutator.apply(current);
            jedis.set(redisKey(key), serialise(next));
            return next;
        }
    }

    @Override
    public List<String> keys() {
        List<String> out = new ArrayList<>();
        String cursor = ScanParams.SCAN_POINTER_START;
        ScanParams params = new ScanParams().match(keyPrefix + "*").count(100);
        do {
            ScanResult<String> r = jedis.scan(cursor, params);
            cursor = r.getCursor();
            for (String full : r.getResult()) {
                out.add(full.substring(keyPrefix.length()));
            }
        } while (!cursor.equals(ScanParams.SCAN_POINTER_START));
        return out;
    }

    private String redisKey(String key) {
        return keyPrefix + key;
    }

    private String serialise(V value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialise value", e);
        }
    }

    private Object lockFor(String key) {
        return locks.computeIfAbsent(key, k -> new Object());
    }
}
