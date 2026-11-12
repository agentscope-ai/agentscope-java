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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Public-only payload packages. Event identities and accepted sequences are assigned by SQL. */
final class ManagedFactBundles {
    record Content(String type, String payload) {
        Content {
            if (type == null
                    || type.isBlank()
                    || type.length() > 64
                    || payload == null
                    || payload.isBlank())
                throw new IllegalArgumentException("Invalid platform fact content");
        }

        String hash() {
            return ManagedScope.fingerprint(type, payload);
        }
    }

    record Bundle(int schemaVersion, String scope, List<Content> entries) {}

    record Prepared(RuntimeObjectStore.Reference reference, int ordinal, Content content) {}

    private final RuntimeObjectStore objects;
    private final ObjectMapper mapper;

    ManagedFactBundles(RuntimeObjectStore objects, ObjectMapper mapper) {
        this.objects = objects;
        this.mapper = mapper;
    }

    Map<String, Prepared> stage(ManagedScope scope, List<Content> contents) {
        var unique = new LinkedHashMap<String, Content>();
        for (var content : contents) unique.putIfAbsent(content.hash(), content);
        var result = new LinkedHashMap<String, Prepared>();
        // Bound individual packages independently of the native batch's event count.
        var page = new ArrayList<Content>();
        int size = 0;
        for (var content : unique.values()) {
            int bytes = encode(content).length;
            if (!page.isEmpty() && (size + bytes > 4 * 1024 * 1024 || page.size() >= 128)) {
                save(scope, page, result);
                page.clear();
                size = 0;
            }
            page.add(content);
            size += bytes;
        }
        if (!page.isEmpty()) save(scope, page, result);
        return Map.copyOf(result);
    }

    private void save(ManagedScope scope, List<Content> page, Map<String, Prepared> result) {
        byte[] bytes = encode(new Bundle(1, scope.messageGroup(), List.copyOf(page)));
        var ref =
                objects.put(
                        scope.messageGroup(),
                        "platform-facts",
                        RuntimeObjectStore.sha256(bytes),
                        bytes);
        for (int i = 0; i < page.size(); i++)
            result.put(page.get(i).hash(), new Prepared(ref, i, page.get(i)));
    }

    Bundle read(ManagedScope scope, RuntimeObjectStore.Reference ref) {
        if (!scope.messageGroup().equals(ref.scope()) || !"platform-facts".equals(ref.kind()))
            throw new ManagedStateException(
                    "projection_object_invalid", "Fact package scope or kind differs");
        byte[] bytes = objects.read(ref);
        try {
            var bundle = mapper.readValue(bytes, Bundle.class);
            if (bundle.schemaVersion() != 1
                    || !scope.messageGroup().equals(bundle.scope())
                    || bundle.entries() == null
                    || bundle.entries().isEmpty()
                    || bundle.entries().size() > 128
                    || bundle.entries().stream().anyMatch(Objects::isNull))
                throw new ManagedStateException(
                        "projection_object_invalid", "Invalid fact package envelope");
            return bundle;
        } catch (ManagedStateException invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw new ManagedStateException(
                    "projection_object_invalid", "Cannot decode committed fact package");
        }
    }

    byte[] encode(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Cannot encode fact package", invalid);
        }
    }
}
