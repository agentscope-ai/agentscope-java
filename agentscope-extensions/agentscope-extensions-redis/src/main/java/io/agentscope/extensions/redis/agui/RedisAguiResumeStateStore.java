/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.redis.agui;

import com.fasterxml.jackson.core.type.TypeReference;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.processor.AguiResumeStateStore;
import io.agentscope.core.util.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import redis.clients.jedis.UnifiedJedis;

/**
 * Redis-backed {@link AguiResumeStateStore}.
 *
 * <p>Each thread is stored in one Redis hash. Run claims, owner-checked releases, and
 * owner-checked pending interrupt replacements each execute as a single Lua script so replicas
 * cannot interleave the read and mutation.
 *
 * <p>Example:
 *
 * <pre>{@code
 * AguiResumeStateStore resumeStore = new RedisAguiResumeStateStore(jedis);
 * AguiRequestProcessor processor = AguiRequestProcessor.builder()
 *     .agentResolver(resolver)
 *     .resumeStateStore(resumeStore)
 *     .build();
 * }</pre>
 */
public final class RedisAguiResumeStateStore implements AguiResumeStateStore {

    /** Default key prefix for AG-UI resume coordination state. */
    public static final String DEFAULT_KEY_PREFIX = "agentscope:agui:resume:";

    private static final String ACTIVE_RUN_FIELD = "activeRunId";
    private static final String PENDING_INTERRUPTS_FIELD = "pendingInterrupts";

    private static final String CLAIM_RUN_SCRIPT =
            "local owner = redis.call('HGET', KEYS[1], ARGV[1]) "
                    + "if owner then return owner end "
                    + "redis.call('HSET', KEYS[1], ARGV[1], ARGV[2]) "
                    + "return false";

    private static final String RELEASE_RUN_SCRIPT =
            "local owner = redis.call('HGET', KEYS[1], ARGV[1]) "
                    + "if owner ~= ARGV[2] then return 0 end "
                    + "redis.call('HDEL', KEYS[1], ARGV[1]) "
                    + "if redis.call('HLEN', KEYS[1]) == 0 then redis.call('DEL', KEYS[1]) end "
                    + "return 1";

    private static final String REPLACE_PENDING_SCRIPT =
            "local owner = redis.call('HGET', KEYS[1], ARGV[1]) "
                    + "if owner ~= ARGV[2] then return 0 end "
                    + "if ARGV[4] == '1' then "
                    + "redis.call('HDEL', KEYS[1], ARGV[3]) "
                    + "else redis.call('HSET', KEYS[1], ARGV[3], ARGV[5]) end "
                    + "return 1";

    private static final TypeReference<Map<String, AguiEvent.Interrupt>> INTERRUPTS_TYPE =
            new TypeReference<>() {};

    private final UnifiedJedis jedis;
    private final String keyPrefix;

    /** Create a store with the {@linkplain #DEFAULT_KEY_PREFIX default key prefix}. */
    public RedisAguiResumeStateStore(UnifiedJedis jedis) {
        this(jedis, DEFAULT_KEY_PREFIX);
    }

    /**
     * Create a store with a custom key prefix.
     *
     * @param jedis initialized Jedis client
     * @param keyPrefix prefix for AG-UI resume state keys
     */
    public RedisAguiResumeStateStore(UnifiedJedis jedis, String keyPrefix) {
        this.jedis = Objects.requireNonNull(jedis, "jedis");
        this.keyPrefix = normalizePrefix(keyPrefix);
    }

    @Override
    public Map<String, AguiEvent.Interrupt> getPendingInterrupts(String threadId) {
        String json = jedis.hget(key(threadId), PENDING_INTERRUPTS_FIELD);
        if (json == null || json.isEmpty()) {
            return Map.of();
        }
        Map<String, AguiEvent.Interrupt> interrupts =
                JsonUtils.getJsonCodec().fromJson(json, INTERRUPTS_TYPE);
        return interrupts == null || interrupts.isEmpty() ? Map.of() : Map.copyOf(interrupts);
    }

    @Override
    public RunClaim claimRun(String threadId, String runId) {
        requireIdentifier(runId, "runId");
        Object result =
                jedis.eval(
                        CLAIM_RUN_SCRIPT, List.of(key(threadId)), List.of(ACTIVE_RUN_FIELD, runId));
        String activeRunId = asString(result);
        return activeRunId == null ? RunClaim.acquired() : RunClaim.rejected(activeRunId);
    }

    @Override
    public void releaseRun(String threadId, String runId) {
        requireIdentifier(runId, "runId");
        jedis.eval(RELEASE_RUN_SCRIPT, List.of(key(threadId)), List.of(ACTIVE_RUN_FIELD, runId));
    }

    @Override
    public boolean replacePendingInterrupts(
            String threadId, String runId, Map<String, AguiEvent.Interrupt> pendingInterrupts) {
        requireIdentifier(runId, "runId");
        Map<String, AguiEvent.Interrupt> snapshot =
                Map.copyOf(Objects.requireNonNull(pendingInterrupts, "pendingInterrupts"));
        boolean clear = snapshot.isEmpty();
        String json = clear ? "" : JsonUtils.getJsonCodec().toJson(snapshot);
        Object result =
                jedis.eval(
                        REPLACE_PENDING_SCRIPT,
                        List.of(key(threadId)),
                        List.of(
                                ACTIVE_RUN_FIELD,
                                runId,
                                PENDING_INTERRUPTS_FIELD,
                                clear ? "1" : "0",
                                json));
        return asLong(result) == 1L;
    }

    private String key(String threadId) {
        requireIdentifier(threadId, "threadId");
        return keyPrefix + threadId;
    }

    private static void requireIdentifier(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return DEFAULT_KEY_PREFIX;
        }
        return prefix.endsWith(":") ? prefix : prefix + ":";
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return value.toString();
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        String string = asString(value);
        return string != null ? Long.parseLong(string) : 0L;
    }
}
