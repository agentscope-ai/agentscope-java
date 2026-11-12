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
package io.agentcore.managed.server.runtime.managed;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Scoped immutable bytes. A successful save stages content; only a metadata commit makes it live. */
public interface RuntimeObjectStore extends AutoCloseable {
    int MAX_BYTES = 64 * 1024 * 1024;

    record Reference(
            String backendId, String scope, String kind, String key, String sha256, long size) {
        public Reference {
            validateBackend(backendId);
            objectKey(scope, kind, key);
            if (sha256 == null || !sha256.matches("[a-f0-9]{64}") || size < 1 || size > MAX_BYTES)
                throw new IllegalArgumentException("Invalid object digest or size");
        }
    }

    String backendId();

    Reference put(String scope, String kind, String key, byte[] content);

    byte[] read(String scope, String kind, String key);

    boolean exists(String scope, String kind, String key);

    default byte[] read(Reference reference) {
        if (!backendId().equals(reference.backendId()))
            throw new ManagedStateException(
                    "object_backend_mismatch", "Object belongs to a different backend");
        byte[] content = read(reference.scope(), reference.kind(), reference.key());
        if (content.length != reference.size() || !sha256(content).equals(reference.sha256()))
            throw new ManagedStateException(
                    "runtime_object_invalid", "Object hash or size differs");
        return content;
    }

    default void putState(String scope, String hash, byte[] content) {
        if (!ManagedScope.fingerprint(new String(content, StandardCharsets.UTF_8)).equals(hash))
            throw new IllegalArgumentException("State hash differs");
        put(scope, "state", hash, content);
    }

    default boolean hasState(String scope, String hash) {
        return exists(scope, "state", hash);
    }

    default byte[] readState(String scope, String hash) {
        byte[] content = read(scope, "state", hash);
        if (!ManagedScope.fingerprint(new String(content, StandardCharsets.UTF_8)).equals(hash))
            throw new ManagedStateException("runtime_object_invalid", "Shared state hash differs");
        return content;
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static void validateBackend(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}"))
            throw new IllegalArgumentException("Invalid backend identity");
    }

    static String objectKey(String scope, String kind, String key) {
        if (scope == null
                || !scope.matches("[A-Za-z0-9_-]{1,128}")
                || kind == null
                || !kind.matches("[a-z][a-z_-]{0,31}")
                || key == null
                || !key.matches("[A-Za-z0-9_.:-]{1,256}")
                || key.equals(".")
                || key.equals(".."))
            throw new IllegalArgumentException("Invalid scoped object identity");
        return scope + "/" + kind + "/" + key;
    }

    @Override
    default void close() {}
}
