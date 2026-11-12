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
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/** Executable wire contract shared by local components and transport adapters. */
public final class DurableProtocol {
    public static final String VERSION = "durable.v1";

    public enum Endpoint {
        USER,
        BACKEND,
        EXECUTOR
    }

    public record Request(String version, String operation, JsonNode payload) {}

    public record Response(String version, JsonNode result) {}

    public record Error(String version, DurableException.Code code, String message) {}

    private static final String SCHEMA_ID = "https://agentscope.io/durable/v1/schema.json";
    private static final String LOCATION = SCHEMA_ID + "#/$defs/";
    private static final SchemaRegistry REGISTRY =
            SchemaRegistry.withDefaultDialect(
                    SpecificationVersion.DRAFT_2020_12,
                    builder -> builder.schemas(Map.of(SCHEMA_ID, schema())));

    private static String schema() {
        try (var input =
                DurableProtocol.class.getResourceAsStream("/durable-protocol/schema.json")) {
            if (input == null)
                throw new IllegalStateException("Packaged Durable schema is missing");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static final Map<String, Predicate<JsonNode>> VALIDATORS = new ConcurrentHashMap<>();

    private DurableProtocol() {}

    /** Reject invalid input before accessing a store or an external executor. */
    public static void validate(String definition, Object value) {
        var valid =
                VALIDATORS.computeIfAbsent(
                        definition,
                        name -> {
                            var schema = REGISTRY.getSchema(SchemaLocation.of(LOCATION + name));
                            return node -> schema.validate(node).isEmpty();
                        });
        var node = DurableJson.value(value);
        DurableJson.requirePortableNumbers(node);
        if (!valid.test(node))
            throw new DurableException(
                    DurableException.Code.INVALID,
                    "Value does not conform to Durable " + definition);
    }

    public static JsonNode request(Endpoint endpoint, String operation, Object payload) {
        JsonNode request =
                DurableJson.value(new Request(VERSION, operation, DurableJson.value(payload)));
        validateRequest(endpoint, request);
        return request;
    }

    public static void validateRequest(Endpoint endpoint, JsonNode request) {
        requireVersion(request);
        validate(prefix(endpoint) + "Request", request);
    }

    /** A malformed successful response has an uncertain outcome, especially for writes. */
    public static JsonNode response(Endpoint endpoint, String operation, JsonNode response) {
        requireVersion(response);
        try {
            validate(prefix(endpoint) + "_" + operation + "Response", response);
        } catch (RuntimeException error) {
            throw new DurableException(
                    DurableException.Code.UNAVAILABLE,
                    "Remote Durable response is invalid; outcome is unconfirmed",
                    error);
        }
        return response.get("result");
    }

    private static void requireVersion(JsonNode envelope) {
        if (envelope == null || !VERSION.equals(envelope.path("version").asText()))
            throw new DurableException(
                    DurableException.Code.INCOMPATIBLE, "Unknown Durable protocol version");
    }

    private static String prefix(Endpoint endpoint) {
        return switch (endpoint) {
            case USER -> "user";
            case BACKEND -> "backend";
            case EXECUTOR -> "executor";
        };
    }
}
