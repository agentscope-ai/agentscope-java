/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
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
package io.agentscope.core.state;

import io.agentscope.core.util.JsonUtils;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Utility class for computing hash values of state lists.
 *
 * <p>This class provides hash computation for change detection in AgentStateStore implementations. The hash
 * is used to detect if a list has been modified (not just appended) since the last save operation.
 *
 * <p>The hash is a full content hash computed over every element (serialized to JSON), so any
 * modification to any element is detected. It must NOT use sampling: a sampled hash can silently
 * miss edits to non-sampled positions and cause stale data to be kept on save.
 *
 * <p>The digest is SHA-256 (256-bit, hex-encoded), streamed element by element so no single
 * large string is materialized; the previous 32-bit {@code String.hashCode()} digest had a
 * non-trivial collision risk, and a collision silently keeps a stale list on save.
 *
 * <p>Stores on the hot write path should serialize once and reuse: call {@link #serialize(List)}
 * to obtain the per-element JSON, feed it to {@link #computeHashOfSerialized(List)} /
 * {@link #needsFullRewriteSerialized(List, String, int)}, and pass the same strings to the
 * persistence layer. That avoids serializing every element 2-3 times per save.
 *
 * <p>Usage in AgentStateStore implementations:
 *
 * <pre>{@code
 * List<String> json = ListHashUtil.serialize(values);
 * String currentHash = ListHashUtil.computeHashOfSerialized(json);
 * String storedHash = readStoredHash();
 *
 * if (storedHash != null && !storedHash.equals(currentHash)) {
 *     // List was modified, need full rewrite
 *     rewriteEntireList(json);
 * } else if (values.size() > existingCount) {
 *     // List grew, can append incrementally
 *     appendNewItems(json.subList(existingCount, json.size()));
 * }
 * }</pre>
 */
public final class ListHashUtil {

    /** Empty list hash constant. */
    private static final String EMPTY_HASH = "empty:0";

    private ListHashUtil() {
        // Utility class, prevent instantiation
    }

    /**
     * Serialize every element of a state list to its JSON form (the literal string {@code "null"}
     * for null elements). The result is a plain {@code List<String>} that can be reused for
     * hashing and for the write itself, so each element is serialized only once per save.
     *
     * @param values the list of state objects (may be null)
     * @return the serialized elements, or null when {@code values} is null
     */
    public static List<String> serialize(List<? extends State> values) {
        if (values == null) {
            return null;
        }
        List<String> out = new ArrayList<>(values.size());
        for (State item : values) {
            out.add(item != null ? JsonUtils.getJsonCodec().toJson(item) : "null");
        }
        return out;
    }

    /**
     * Compute a hash value for a list of state objects.
     *
     * <p>The hash includes:
     *
     * <ul>
     *   <li>List size
     *   <li>The serialized (JSON) form of every element, at every position
     * </ul>
     *
     * <p>Every element is included so that a modification at any position is detected. Detection
     * is based on the serialized form rather than {@link Object#hashCode()}, so it does not depend
     * on whether {@code State} implementations override {@code hashCode()} content-wise.
     *
     * <p>Equivalent to {@code computeHashOfSerialized(serialize(values))}. Stores that also need
     * the serialized elements for the write itself should call {@link #serialize(List)} once and
     * {@link #computeHashOfSerialized(List)} instead, to avoid serializing twice.
     *
     * @param values the list of state objects to hash
     * @return a hex string hash representing the list content
     */
    public static String computeHash(List<? extends State> values) {
        if (values == null || values.isEmpty()) {
            return EMPTY_HASH;
        }
        return computeHashOfSerialized(serialize(values));
    }

    /**
     * Compute the hash of an already-serialized list, streaming every element into a SHA-256
     * digest (hex-encoded, 64 chars).
     *
     * <p>The stream layout is {@code size:N;} followed by {@code idx:json,} per element — the same
     * logical content the legacy 32-bit implementation covered, so the semantics (any change at
     * any position changes the hash) are unchanged. Hashes written by the previous 32-bit
     * implementation will simply not match, which triggers one safe full rewrite per list on the
     * first save after upgrade.
     *
     * @param jsonItems the serialized elements (produced by {@link #serialize(List)})
     * @return a 64-character hex string hash representing the list content
     */
    public static String computeHashOfSerialized(List<String> jsonItems) {
        if (jsonItems == null || jsonItems.isEmpty()) {
            return EMPTY_HASH;
        }
        MessageDigest md = newSha256();
        md.update(("size:" + jsonItems.size() + ";").getBytes(StandardCharsets.UTF_8));
        for (int idx = 0; idx < jsonItems.size(); idx++) {
            String json = jsonItems.get(idx);
            md.update(String.valueOf(idx).getBytes(StandardCharsets.UTF_8));
            md.update((byte) ':');
            md.update((json != null ? json : "null").getBytes(StandardCharsets.UTF_8));
            md.update((byte) ',');
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /**
     * Check if the list has changed based on hash comparison.
     *
     * @param currentHash the hash of the current list
     * @param storedHash the previously stored hash (may be null)
     * @return true if the list has changed, false otherwise
     */
    public static boolean hasChanged(String currentHash, String storedHash) {
        if (storedHash == null) {
            // No previous hash, consider as new list
            return false;
        }
        return !storedHash.equals(currentHash);
    }

    /**
     * Determine if a full rewrite is needed based on list content and existing count.
     *
     * @param currentValues the current complete list of state objects
     * @param storedHash the previously stored hash (may be null)
     * @param existingCount the count of items already stored
     * @return true if full rewrite is needed, false if incremental append is sufficient
     */
    public static boolean needsFullRewrite(
            List<? extends State> currentValues, String storedHash, int existingCount) {
        return needsFullRewriteSerialized(serialize(currentValues), storedHash, existingCount);
    }

    /**
     * Determine if a full rewrite is needed, given the already-serialized current list. Mirrors
     * {@link #needsFullRewrite(List, String, int)} but reuses the caller's serialization, so a
     * store that also writes these same JSON strings does not serialize the prefix a second time.
     *
     * @param currentJson the current complete list, serialized via {@link #serialize(List)}
     * @param storedHash the previously stored hash (may be null)
     * @param existingCount the count of items already stored
     * @return true if full rewrite is needed, false if incremental append is sufficient
     */
    public static boolean needsFullRewriteSerialized(
            List<String> currentJson, String storedHash, int existingCount) {
        if (currentJson == null) {
            return existingCount > 0;
        }

        int currentSize = currentJson.size();

        // Case 1: List shrunk (items were deleted)
        if (currentSize < existingCount) {
            return true;
        }

        // Case 2: Missing hash but existing data found (e.g., version upgrade or corrupted hash)
        // Must rewrite because we cannot verify unmodified state.
        if (storedHash == null && existingCount > 0) {
            return true;
        }

        // Case 3: Check if the previously existing elements were modified. subList is a view, so
        // no element is copied or re-serialized for the prefix hash.
        String prefixHash = computeHashOfSerialized(currentJson.subList(0, existingCount));
        return hasChanged(prefixHash, storedHash);
    }

    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated for every JVM; unreachable in practice.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
