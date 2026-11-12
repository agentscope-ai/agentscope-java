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

import com.fasterxml.jackson.databind.JsonNode;
import io.agentcore.managed.harness.runtime.SessionServiceClient;
import java.util.Map;

/** Retains platform conflict codes across HTTP so local recovery decisions remain unchanged. */
final class ManagedServiceCalls {
    private ManagedServiceCalls() {}

    static JsonNode coordinate(SessionServiceClient client, String operation, Object data) {
        var response =
                post(
                        client,
                        "prepared/coordination",
                        Map.of("protocolVersion", "0.1.0", "operation", operation, "data", data));
        if (!"0.1.0".equals(response.path("protocolVersion").asText()) || !response.has("result"))
            throw new IllegalStateException("Invalid coordination response");
        return response.get("result");
    }

    static JsonNode post(SessionServiceClient client, String path, Object request) {
        try {
            return client.post(path, request);
        } catch (SessionServiceClient.Rejected rejected) {
            if (rejected.status() == 409)
                throw new ManagedStateException(
                        rejected.code(), "Session Service rejected the state transition");
            throw rejected;
        }
    }
}
