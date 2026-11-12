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
package io.agentcore.managed.harness.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Schemas are packaged from protocol/, never fetched from the network. */
public final class ProtocolSchemas {
    private static final String ROOT = "https://schemas.agentcore.invalid/managed-agents/0.1.0/";
    private final SchemaRegistry factory =
            SchemaRegistry.withDefaultDialect(
                    SpecificationVersion.DRAFT_2020_12,
                    builder ->
                            builder.schemaIdResolvers(
                                    mappers ->
                                            mappers.mapPrefix(
                                                    ROOT, "classpath:harness-protocol/")));
    private final Map<String, Schema> schemas = new ConcurrentHashMap<>();
    private static final Map<String, String> REQUESTS =
            Map.ofEntries(
                    Map.entry("executions/claim", "execution/ClaimRequest"),
                    Map.entry("prepared/coordination", "coordination/CoordinationRequest"),
                    Map.entry("executions/renew", "execution/RenewRequest"),
                    Map.entry("executions/release", "runtime/FencedRequest"),
                    Map.entry("executions/next", "runtime/NextRequest"),
                    Map.entry("executions/configuration", "runtime/FencedRequest"),
                    Map.entry("deliveries/defer", "execution/DeferRequest"),
                    Map.entry("commits", "commit/CommitBoundary"),
                    Map.entry("commits/receipt", "commit/ReceiptRequest"),
                    Map.entry("recovery/read", "recovery/RecoveryRequest"),
                    Map.entry("objects/write", "common/ObjectWriteRequest"),
                    Map.entry("objects/read", "common/ObjectReadRequest"),
                    Map.entry("tools/state", "tools/ToolStateRequest"),
                    Map.entry("inputs/read", "runtime/FencedRequest"),
                    Map.entry("inputs/close", "runtime/FencedRequest"),
                    Map.entry("models/reserve", "runtime/ReserveModelRequest"),
                    Map.entry("previews/interest", "preview/InterestRequest"),
                    Map.entry("previews/publish", "preview/PublishRequest"));

    public void request(String operation, JsonNode request) {
        String schema = REQUESTS.get(operation);
        if (schema == null) throw new IllegalArgumentException("Unknown operation");
        int limit = operation.equals("objects/write") ? 90 * 1024 * 1024 : 1024 * 1024;
        if (operation.startsWith("previews/")) limit = 64 * 1024;
        if (request.toString().getBytes(StandardCharsets.UTF_8).length > limit)
            throw new IllegalArgumentException("Request exceeds protocol limit");
        validate(schema, request);
    }

    public void validate(String name, JsonNode value) {
        var schema =
                schemas.computeIfAbsent(
                        name,
                        key -> {
                            var parts = key.split("/", 2);
                            return factory.getSchema(
                                    SchemaLocation.of(
                                            ROOT + parts[0] + ".schema.json#/$defs/" + parts[1]));
                        });
        var errors =
                schema.validate(
                        value,
                        context ->
                                context.executionConfig(
                                        config -> config.formatAssertionsEnabled(true)));
        if (!errors.isEmpty())
            throw new IllegalArgumentException("Invalid protocol record: " + name);
    }
}
