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

import java.util.Optional;

/**
 * Builds Redis keys for agent state sessions.
 *
 * <p>V0 is the original layout, where all session keys use the plain session segment
 * {@code userId/sessionId}. V1 is the Redis Cluster-safe layout, where versioned state keys use the
 * same segment wrapped in a Redis hash tag: {@code {userId/sessionId}}. Keeping the tag
 * content equal to the v0 segment lets the state key, version key, and session marker share one hash
 * slot for Lua CAS operations.
 */
final class RedisAgentStateKeyLayout {

    static final String KEYS_SUFFIX = ":_keys";

    private static final String LIST_SUFFIX = ":list";

    private static final String ANON_USER = "__anon__";

    private final String keyPrefix;

    private final String slotId;

    private RedisAgentStateKeyLayout(String keyPrefix, String slotId) {
        this.keyPrefix = keyPrefix;
        this.slotId = slotId;
    }

    /**
     * Build a v1 layout using a Redis hash tag around the normalized session segment.
     *
     * <p>Example: {@code agentscope:session:{user/session}:state}.
     *
     * @param keyPrefix Redis key prefix configured for the state store
     * @param userId original user id, nullable
     * @param sessionId session id, required
     * @return v1 cluster-safe key layout
     */
    static RedisAgentStateKeyLayout v1(String keyPrefix, String userId, String sessionId) {
        validateV1KeyPrefix(keyPrefix);
        validateV1UserId(normalizeUser(userId));
        validateV1SessionId(sessionId);
        return new RedisAgentStateKeyLayout(
                keyPrefix, "{" + sessionSegment(userId, sessionId) + "}");
    }

    /**
     * Build a v0 layout using the original non-hash-tagged session segment.
     *
     * <p>Example: {@code agentscope:session:user/session:state}.
     *
     * @param keyPrefix Redis key prefix configured for the state store
     * @param userId original user id, nullable
     * @param sessionId session id, required
     * @return original v0 key layout
     */
    static RedisAgentStateKeyLayout v0(String keyPrefix, String userId, String sessionId) {
        return new RedisAgentStateKeyLayout(keyPrefix, sessionSegment(userId, sessionId));
    }

    /**
     * Normalize absent user ids to the same sentinel used by the original Redis state layout.
     *
     * @param userId original user id, nullable
     * @return normalized user segment
     */
    static String normalizeUser(String userId) {
        return userId == null || userId.isBlank() ? ANON_USER : userId;
    }

    /**
     * Build the key pattern used to scan v1 session marker keys for a user segment.
     *
     * @param keyPrefix Redis key prefix configured for the state store
     * @param userSegment normalized user segment
     * @return Redis scan pattern for v1 session marker keys
     */
    static String v1KeysPattern(String keyPrefix, String userSegment) {
        return escapeRedisGlobLiteral(keyPrefix)
                + "{"
                + escapeRedisGlobLiteral(userSegment)
                + "/*}"
                + KEYS_SUFFIX;
    }

    /**
     * Build the key pattern used to scan v0 session marker keys for a user segment.
     *
     * @param keyPrefix Redis key prefix configured for the state store
     * @param userSegment normalized user segment
     * @return Redis scan pattern for v0 session marker keys
     */
    static String v0KeysPattern(String keyPrefix, String userSegment) {
        return escapeRedisGlobLiteral(keyPrefix)
                + escapeRedisGlobLiteral(userSegment)
                + "/*"
                + KEYS_SUFFIX;
    }

    /**
     * Validate that a v1 prefix cannot alter the Redis hash tag added around a session segment.
     *
     * @param keyPrefix Redis key prefix configured for the state store
     * @throws IllegalArgumentException when the prefix contains a hash-tag brace
     */
    static void validateV1KeyPrefix(String keyPrefix) {
        rejectCharacters(keyPrefix, "keyPrefix", false);
    }

    /**
     * Validate that a normalized v1 user id is unambiguous inside {@code {userId/sessionId}}.
     *
     * @param userId normalized user id
     * @throws IllegalArgumentException when the id contains a brace or path separator
     */
    static void validateV1UserId(String userId) {
        rejectCharacters(userId, "userId", true);
    }

    private static void validateV1SessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        rejectCharacters(sessionId, "sessionId", true);
    }

    private static void rejectCharacters(String value, String name, boolean rejectSlash) {
        if (value.indexOf('{') >= 0
                || value.indexOf('}') >= 0
                || (rejectSlash && value.indexOf('/') >= 0)) {
            throw new IllegalArgumentException(
                    name + ": " + value + " contains a character that is not supported by V1");
        }
    }

    /**
     * Escape Redis glob metacharacters so a value is matched literally by {@code SCAN MATCH}.
     *
     * <p>This transforms only the temporary scan pattern. It does not modify the caller's value or
     * the Redis key stored for that value.
     *
     * @param value literal key segment to embed in a Redis glob pattern
     * @return the segment encoded for literal matching in a Redis glob pattern
     */
    private static String escapeRedisGlobLiteral(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == '\\'
                    || character == '*'
                    || character == '?'
                    || character == '['
                    || character == ']') {
                escaped.append('\\');
            }
            escaped.append(character);
        }
        return escaped.toString();
    }

    /**
     * Parse a v0 session marker key and return the session id when it matches the user segment.
     *
     * @param keysKey Redis session marker key
     * @param keyPrefix Redis key prefix configured for the state store
     * @param userSegment normalized user segment
     * @return parsed session id, or empty when the key does not match v0 layout
     */
    static Optional<String> parseV0SessionIdFromKeysKey(
            String keysKey, String keyPrefix, String userSegment) {
        String userPrefix = keyPrefix + userSegment + "/";
        if (!keysKey.startsWith(userPrefix) || !keysKey.endsWith(KEYS_SUFFIX)) {
            return Optional.empty();
        }
        return Optional.of(
                keysKey.substring(userPrefix.length(), keysKey.length() - KEYS_SUFFIX.length()));
    }

    /**
     * Parse a v1 session marker key and return the session id without Redis hash-tag braces.
     *
     * @param keysKey Redis session marker key
     * @param keyPrefix Redis key prefix configured for the state store
     * @param userSegment normalized user segment
     * @return parsed session id, or empty when the key does not match v1 layout
     */
    static Optional<String> parseV1SessionIdFromKeysKey(
            String keysKey, String keyPrefix, String userSegment) {
        String userPrefix = keyPrefix + "{" + userSegment + "/";
        String suffix = "}" + KEYS_SUFFIX;
        if (!keysKey.startsWith(userPrefix) || !keysKey.endsWith(suffix)) {
            return Optional.empty();
        }
        return Optional.of(
                keysKey.substring(userPrefix.length(), keysKey.length() - suffix.length()));
    }

    /**
     * Build the Redis string key for a single state value in this layout.
     *
     * @param key state key within the session
     * @return Redis key for the single state value
     */
    String getStateKey(String key) {
        return keyPrefix + slotId + ":" + key;
    }

    /**
     * Build the Redis list key for a list state value in this layout.
     *
     * @param key state key within the session
     * @return Redis key for the list state value
     */
    String getListKey(String key) {
        return getStateKey(key) + LIST_SUFFIX;
    }

    /**
     * Build the session marker member used to track a list state key.
     *
     * @param key state key within the session
     * @return marker member for a list state key
     */
    String getListTrackKey(String key) {
        return key + LIST_SUFFIX;
    }

    /**
     * Return whether a session marker member represents a list state key.
     *
     * @param trackedKey member stored in the session marker set
     * @return true when the marker member represents list state
     */
    static boolean isListTrackKey(String trackedKey) {
        return trackedKey.endsWith(LIST_SUFFIX);
    }

    /**
     * Return the state key name represented by a list marker member.
     *
     * @param trackedKey list marker member stored in the session marker set
     * @return state key name without the list marker suffix
     */
    static String baseKeyFromListTrackKey(String trackedKey) {
        return trackedKey.substring(0, trackedKey.length() - LIST_SUFFIX.length());
    }

    /**
     * Build the Redis set key that tracks all state keys for this session layout.
     *
     * @return Redis key for the session marker set
     */
    String getKeysKey() {
        return keyPrefix + slotId + KEYS_SUFFIX;
    }

    /**
     * Return the normalized session segment used by this layout.
     *
     * @return slot id segment for this key layout
     */
    String slotId() {
        return slotId;
    }

    /**
     * Build the normalized {@code userId/sessionId} segment shared by v0 and v1 layouts.
     *
     * @param userId original user id, nullable
     * @param sessionId session id, required
     * @return normalized session segment
     */
    private static String sessionSegment(String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        return normalizeUser(userId) + "/" + sessionId;
    }
}
