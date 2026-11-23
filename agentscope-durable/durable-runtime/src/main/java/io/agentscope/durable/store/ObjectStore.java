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
package io.agentscope.durable.store;

import io.agentscope.durable.DurableException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Immutable, scoped bytes. Upload alone does not commit a checkpoint or authorize an effect. */
public interface ObjectStore {
    int MAX_BYTES = 64 * 1024 * 1024;

    record Reference(String store, String scope, String digest, long size) {
        public Reference {
            if (store == null
                    || !store.matches("[A-Za-z0-9_-]{1,64}")
                    || scope == null
                    || !scope.matches("[a-f0-9]{64}")
                    || digest == null
                    || !digest.matches("[a-f0-9]{64}")
                    || size < 0
                    || size > MAX_BYTES)
                throw new IllegalArgumentException("Invalid immutable object reference");
        }

        public String key() {
            return scope + "/" + digest;
        }
    }

    String id();

    Reference put(String scope, byte[] content);

    byte[] read(Reference reference);

    static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static Reference reference(String id, String scope, byte[] bytes) {
        return new Reference(id, scope, digest(bytes), bytes.length);
    }

    static byte[] verify(String id, Reference reference, byte[] bytes) {
        if (!id.equals(reference.store())
                || reference.size() != bytes.length
                || !reference.digest().equals(digest(bytes)))
            throw new DurableException(
                    DurableException.Code.INVALID, "Object scope, size or digest differs");
        return bytes;
    }
}
