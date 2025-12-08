/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.session.redis.jedis;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.session.Session;
import io.agentscope.core.session.SessionInfo;
import io.agentscope.core.state.StateModule;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.resps.ScanResult;

/**
 * Redis-based session implementation using Jedis.
 *
 * <p>This implementation stores session state as JSON strings in Redis. Each session is stored as a
 * single JSON value under a Redis key derived from the session ID.
 *
 * <p>Features:
 *
 * <ul>
 *   <li>Multi-module session support
 *   <li>JSON-encoded session state
 *   <li>Configurable Redis connection and key prefix
 *   <li>Graceful handling of missing sessions
 * </ul>
 */
public class JedisSession implements Session {

    private static final String DEFAULT_KEY_PREFIX = "agentscope:session:";
    private static final String META_SUFFIX = ":meta";
    private static final String META_FIELD_LAST_MODIFIED = "lastModified";
    private static final String SCAN_POINTER_START = "0";

    private final JedisPool jedisPool;
    private final ObjectMapper objectMapper;
    private final String keyPrefix;

    private JedisSession(Builder builder) {
        if (builder.keyPrefix == null || builder.keyPrefix.trim().isEmpty()) {
            throw new IllegalArgumentException("Key prefix cannot be null or empty");
        }
        if (builder.jedisPool == null) {
            throw new IllegalArgumentException("JedisPool cannot be null");
        }
        this.keyPrefix = builder.keyPrefix;
        this.objectMapper = new ObjectMapper();
        this.jedisPool = builder.jedisPool;
    }

    /**
     * Creates a new builder for {@link JedisSession}.
     *
     * @return a new Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Save the state of multiple StateModules to Redis.
     *
     * <p>This implementation persists the state of all provided StateModules as a single JSON
     * string stored under a Redis key derived from the session ID. The method collects state
     * dictionaries from all modules and writes them as a JSON object.
     *
     * @param sessionId Unique identifier for the session
     * @param stateModules Map of component names to StateModule instances
     * @throws RuntimeException if Redis operations fail
     */
    @Override
    public void saveSessionState(String sessionId, Map<String, StateModule> stateModules) {
        validateSessionId(sessionId);

        try {
            Map<String, Object> sessionState = new HashMap<>();
            for (Map.Entry<String, StateModule> entry : stateModules.entrySet()) {
                sessionState.put(entry.getKey(), entry.getValue().stateDict());
            }

            String sessionKey = getSessionKey(sessionId);
            String metaKey = getMetaKey(sessionId);

            String json =
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(sessionState);

            long now = System.currentTimeMillis();

            try (Jedis jedis = getJedisResource()) {
                jedis.set(sessionKey, json);

                Map<String, String> meta = new HashMap<>();
                meta.put(META_FIELD_LAST_MODIFIED, String.valueOf(now));
                jedis.hset(metaKey, meta);
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to save session: " + sessionId, e);
        }
    }

    /**
     * Load session state from Redis into multiple StateModules.
     *
     * <p>This implementation restores the state of all provided StateModules from a JSON string
     * stored in Redis. The method reads the JSON value, extracts component states, and loads them
     * into the corresponding StateModule instances using non-strict loading.
     *
     * @param sessionId Unique identifier for the session
     * @param allowNotExist Whether to allow loading from non-existent sessions
     * @param stateModules Map of component names to StateModule instances to load into
     * @throws RuntimeException if Redis operations fail or session doesn't exist when
     *     allowNotExist is false
     */
    @Override
    public void loadSessionState(
            String sessionId, boolean allowNotExist, Map<String, StateModule> stateModules) {
        validateSessionId(sessionId);

        String sessionKey = getSessionKey(sessionId);

        try (Jedis jedis = getJedisResource()) {
            String json = jedis.get(sessionKey);

            if (json == null) {
                if (allowNotExist) {
                    return;
                } else {
                    throw new RuntimeException("Session not found: " + sessionId);
                }
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> sessionState = objectMapper.readValue(json, Map.class);

            for (Map.Entry<String, StateModule> entry : stateModules.entrySet()) {
                String componentName = entry.getKey();
                StateModule module = entry.getValue();

                if (sessionState.containsKey(componentName)) {
                    Object componentState = sessionState.get(componentName);
                    if (componentState instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> componentStateMap =
                                (Map<String, Object>) componentState;
                        module.loadStateDict(componentStateMap, false);
                    }
                }
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to load session: " + sessionId, e);
        }
    }

    /**
     * Check if a session exists in Redis.
     *
     * @param sessionId Unique identifier for the session
     * @return true if the session key exists
     */
    @Override
    public boolean sessionExists(String sessionId) {
        validateSessionId(sessionId);

        String sessionKey = getSessionKey(sessionId);

        try (Jedis jedis = getJedisResource()) {
            return jedis.exists(sessionKey);
        } catch (Exception e) {
            throw new RuntimeException("Failed to check session existence: " + sessionId, e);
        }
    }

    /**
     * Delete a session from Redis.
     *
     * <p>This implementation removes both the session data key and its metadata key.
     *
     * @param sessionId Unique identifier for the session
     * @return true if the session key was deleted
     * @throws RuntimeException if Redis operations fail
     */
    @Override
    public boolean deleteSession(String sessionId) {
        validateSessionId(sessionId);

        String sessionKey = getSessionKey(sessionId);
        String metaKey = getMetaKey(sessionId);

        try (Jedis jedis = getJedisResource()) {
            long deleted = jedis.del(sessionKey, metaKey);
            return deleted > 0;
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete session: " + sessionId, e);
        }
    }

    /**
     * Get a list of all session IDs from Redis.
     *
     * <p>This implementation scans Redis for keys with the configured prefix and returns their
     * session IDs (without the prefix), sorted alphabetically. Metadata keys are ignored.
     *
     * @return List of session IDs, or empty list if no sessions exist
     * @throws RuntimeException if Redis operations fail
     */
    @Override
    public List<String> listSessions() {
        try (Jedis jedis = getJedisResource()) {
            List<String> sessionIds = new ArrayList<>();

            String cursor = SCAN_POINTER_START;

            do {
                ScanResult<String> result = jedis.scan(cursor);
                List<String> keys = result.getResult();

                for (String key : keys) {
                    if (!key.startsWith(keyPrefix)) {
                        continue;
                    }
                    if (key.endsWith(META_SUFFIX)) {
                        continue;
                    }
                    String sessionId = extractSessionId(key);
                    if (sessionId != null) {
                        sessionIds.add(sessionId);
                    }
                }

                cursor = result.getCursor();

            } while (!SCAN_POINTER_START.equals(cursor));

            return sessionIds.stream().sorted().collect(Collectors.toList());

        } catch (Exception e) {
            throw new RuntimeException("Failed to list sessions", e);
        }
    }

    /**
     * Get information about a session stored in Redis.
     *
     * <p>This implementation reads the JSON value from Redis to determine its size in bytes and the
     * number of state components. The last modification time is stored separately in a metadata
     * hash and returned if available.
     *
     * @param sessionId Unique identifier for the session
     * @return Session information including size, last modified time, and component count
     * @throws RuntimeException if Redis operations fail or session doesn't exist
     */
    @Override
    public SessionInfo getSessionInfo(String sessionId) {
        validateSessionId(sessionId);

        String sessionKey = getSessionKey(sessionId);
        String metaKey = getMetaKey(sessionId);

        try (Jedis jedis = getJedisResource()) {
            String json = jedis.get(sessionKey);
            if (json == null) {
                throw new RuntimeException("Session not found: " + sessionId);
            }

            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            long size = bytes.length;

            @SuppressWarnings("unchecked")
            Map<String, Object> sessionState = objectMapper.readValue(json, Map.class);
            int componentCount = sessionState.size();

            long lastModified = 0L;
            String lastModifiedStr = jedis.hget(metaKey, META_FIELD_LAST_MODIFIED);
            if (lastModifiedStr != null) {
                try {
                    lastModified = Long.parseLong(lastModifiedStr);
                } catch (NumberFormatException ignored) {
                    lastModified = 0L;
                }
            }

            return new SessionInfo(sessionId, size, lastModified, componentCount);

        } catch (Exception e) {
            throw new RuntimeException("Failed to get session info: " + sessionId, e);
        }
    }

    /**
     * Validate a session ID format.
     *
     * @param sessionId Session ID to validate
     * @throws IllegalArgumentException if session ID is invalid
     */
    protected void validateSessionId(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("Session ID cannot be null or empty");
        }
        if (sessionId.contains("/") || sessionId.contains("\\")) {
            throw new IllegalArgumentException("Session ID cannot contain path separators");
        }
        if (sessionId.length() > 255) {
            throw new IllegalArgumentException("Session ID cannot exceed 255 characters");
        }
    }

    /**
     * Get the Redis key for a session.
     *
     * @param sessionId Session ID
     * @return Redis key for the session data
     */
    private String getSessionKey(String sessionId) {
        return keyPrefix + sessionId;
    }

    /**
     * Get the Redis key for session metadata.
     *
     * @param sessionId Session ID
     * @return Redis key for the session metadata
     */
    private String getMetaKey(String sessionId) {
        return getSessionKey(sessionId) + META_SUFFIX;
    }

    /**
     * Extract the session ID from a Redis key.
     *
     * @param key Redis key
     * @return Session ID, or null if the key does not match the prefix
     */
    private String extractSessionId(String key) {
        if (!key.startsWith(keyPrefix)) {
            return null;
        }
        String sessionId = key.substring(keyPrefix.length());
        if (sessionId.endsWith(META_SUFFIX)) {
            return null;
        }
        return sessionId;
    }

    /**
     * Clear all sessions stored in Redis (for testing or cleanup).
     *
     * <p>This implementation asynchronously scans for all session keys (both data and metadata) and
     * deletes them.
     *
     * @return Mono that completes with the number of deleted session data keys
     */
    public Mono<Integer> clearAllSessions() {
        return Mono.fromSupplier(
                        () -> {
                            try (Jedis jedis = getJedisResource()) {
                                int deletedSessions = 0;
                                List<String> keysToDelete = new ArrayList<>();
                                List<String> dataKeys = new ArrayList<>();

                                String cursor = SCAN_POINTER_START;

                                do {
                                    ScanResult<String> result = jedis.scan(cursor);
                                    List<String> keys = result.getResult();

                                    if (!keys.isEmpty()) {
                                        for (String key : keys) {
                                            if (!key.startsWith(keyPrefix)) {
                                                continue;
                                            }
                                            keysToDelete.add(key);
                                            if (!key.endsWith(META_SUFFIX)) {
                                                dataKeys.add(key);
                                            }
                                        }
                                    }

                                    cursor = result.getCursor();

                                } while (!SCAN_POINTER_START.equals(cursor));

                                if (!keysToDelete.isEmpty()) {
                                    jedis.del(keysToDelete.toArray(new String[0]));
                                    deletedSessions = dataKeys.size();
                                }

                                return deletedSessions;
                            } catch (Exception e) {
                                throw new RuntimeException("Failed to clear sessions", e);
                            }
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Close the underlying JedisPool.
     */
    @Override
    public void close() {
        jedisPool.close();
    }

    /**
     * Get a Jedis connection from the pool.
     *
     * <p>The pool itself should be fully configured (host, port, password, database, etc.).
     *
     * @return Jedis instance
     */
    private Jedis getJedisResource() {
        return jedisPool.getResource();
    }

    /**
     * Builder for {@link JedisSession}.
     *
     * <p>Usage example:
     *
     * <pre>{@code
     * JedisPool pool = new JedisPool("127.0.0.1", 6379);
     * JedisSession session = JedisSession.builder()
     *     .jedisPool(pool)
     *     .keyPrefix("agentscope:session:")
     *     .build();
     * }</pre>
     */
    public static class Builder {

        private String keyPrefix = DEFAULT_KEY_PREFIX;
        private JedisPool jedisPool;

        /**
         * Sets the key prefix for all session keys.
         *
         * @param keyPrefix the key prefix
         * @return this builder
         */
        public Builder keyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
            return this;
        }

        /**
         * Sets a custom JedisPool instance.
         *
         * <p>The pool should already be fully configured (host, port, password, database, etc.).
         *
         * @param jedisPool the Jedis connection pool
         * @return this builder
         */
        public Builder jedisPool(JedisPool jedisPool) {
            this.jedisPool = jedisPool;
            return this;
        }

        /**
         * Builds a new {@link JedisSession} instance.
         *
         * @return a configured JedisSession
         */
        public JedisSession build() {
            return new JedisSession(this);
        }
    }
}
