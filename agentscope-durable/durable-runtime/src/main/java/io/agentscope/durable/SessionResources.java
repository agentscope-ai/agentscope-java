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
package io.agentscope.durable;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Private, versioned session resources. Requests and their results commit under the Brain lease. */
public final class SessionResources {
    private SessionResources() {}

    public enum Operation {
        GET,
        SEARCH,
        PUT,
        CAS,
        DELETE
    }

    public record Request(
            String requestId,
            Operation operation,
            List<String> namespace,
            String key,
            JsonNode value,
            long expectedVersion,
            int limit,
            int offset) {
        public Request {
            DurableJson.text(requestId, "requestId");
            if (operation == null
                    || namespace == null
                    || namespace.size() > 32
                    || expectedVersion < 0
                    || limit < 0
                    || limit > 1000
                    || offset < 0
                    || offset > 1_000_000)
                throw new IllegalArgumentException("Invalid resource request");
            namespace = List.copyOf(namespace);
            namespace.forEach(part -> DurableJson.text(part, "namespace"));
            if (operation != Operation.SEARCH) DurableJson.text(key, "resource key");
            if (operation == Operation.PUT || operation == Operation.CAS) {
                if (value == null || !value.isObject())
                    throw new IllegalArgumentException("Resource value must be an object");
            }
            value = DurableJson.copy(value);
        }
    }

    public record Value(List<String> namespace, String key, JsonNode value, long version) {
        public Value {
            if (namespace == null || namespace.size() > 32 || value == null || !value.isObject())
                throw new IllegalArgumentException("Invalid resource value");
            namespace = List.copyOf(namespace);
            namespace.forEach(part -> DurableJson.text(part, "namespace"));
            DurableJson.text(key, "resource key");
            value = DurableJson.copy(value);
            if (version < 1) throw new IllegalArgumentException("Invalid resource version");
        }
    }
}
