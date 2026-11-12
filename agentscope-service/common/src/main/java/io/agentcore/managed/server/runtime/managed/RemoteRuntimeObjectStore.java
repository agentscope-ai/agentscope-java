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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.harness.runtime.SessionServiceClient;
import java.util.Base64;
import java.util.Map;

/** Shared native blob storage is owned by Session Service; Brain needs no backend credentials. */
public final class RemoteRuntimeObjectStore implements RuntimeObjectStore {
    private final SessionServiceClient client;
    private final String backend;
    private final ObjectMapper mapper;

    public RemoteRuntimeObjectStore(
            SessionServiceClient client, String backend, ObjectMapper mapper) {
        RuntimeObjectStore.validateBackend(backend);
        this.client = client;
        this.backend = backend;
        this.mapper = mapper;
    }

    public String backendId() {
        return backend;
    }

    public Reference put(String scope, String kind, String key, byte[] content) {
        RuntimeObjectStore.objectKey(scope, kind, key);
        if (content.length < 1 || content.length > MAX_BYTES)
            throw new IllegalArgumentException("Object exceeds limit");
        var result =
                mapper.convertValue(
                        client.post(
                                "prepared/objects",
                                Map.of(
                                        "operation",
                                        "put",
                                        "scopeKey",
                                        scope,
                                        "kind",
                                        kind,
                                        "key",
                                        key,
                                        "dataBase64",
                                        Base64.getEncoder().encodeToString(content))),
                        Reference.class);
        if (!backend.equals(result.backendId())
                || !scope.equals(result.scope())
                || !kind.equals(result.kind())
                || !key.equals(result.key())
                || result.size() != content.length
                || !RuntimeObjectStore.sha256(content).equals(result.sha256()))
            throw new IllegalStateException("Native object receipt differs");
        return result;
    }

    public byte[] read(String scope, String kind, String key) {
        RuntimeObjectStore.objectKey(scope, kind, key);
        byte[] bytes =
                Base64.getDecoder()
                        .decode(
                                client.post(
                                                "prepared/objects",
                                                Map.of(
                                                        "operation",
                                                        "read",
                                                        "scopeKey",
                                                        scope,
                                                        "kind",
                                                        kind,
                                                        "key",
                                                        key))
                                        .required("dataBase64")
                                        .asText());
        if (bytes.length < 1 || bytes.length > MAX_BYTES)
            throw new IllegalStateException("Native object exceeds limit");
        return bytes;
    }

    public boolean exists(String scope, String kind, String key) {
        RuntimeObjectStore.objectKey(scope, kind, key);
        return client.post(
                        "prepared/objects",
                        Map.of("operation", "exists", "scopeKey", scope, "kind", kind, "key", key))
                .asBoolean();
    }
}
