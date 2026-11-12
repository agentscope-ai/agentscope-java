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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.agentscope.durable.DurableTypes.ThreadKey;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DurableProtocolTest {
    @Test
    void delegationProtocolDistinguishesFrozenChildrenFromCancellationBeforeAdmission()
            throws Exception {
        try (var input = getClass().getResourceAsStream("/durable-protocol/vectors.json")) {
            var vector =
                    DurableJson.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                            .required("delegation");
            DurableProtocol.validateRequest(
                    DurableProtocol.Endpoint.BACKEND, vector.required("request"));
            DurableProtocol.validate("delegationView", vector.required("view"));
            DurableProtocol.validate("delegationView", vector.required("cancelled"));
            assertThrows(
                    DurableException.class,
                    () -> DurableProtocol.validate("delegationView", vector.required("invalid")));
            var missing = vector.required("request").deepCopy();
            ((ObjectNode) missing.path("payload").path("target")).remove("child");
            assertThrows(
                    DurableException.class,
                    () ->
                            DurableProtocol.validateRequest(
                                    DurableProtocol.Endpoint.BACKEND, missing));
        }
    }

    @Test
    void modelFactsRequireExplicitRequestIdentityUsageAndExecutionScope() throws Exception {
        try (var input = getClass().getResourceAsStream("/durable-protocol/vectors.json")) {
            var vectors =
                    DurableJson.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                            .required("modelFacts");
            for (var event : vectors.required("valid"))
                DurableProtocol.validate("eventInput", event);
            for (var event : vectors.required("invalid"))
                assertThrows(
                        DurableException.class,
                        () -> DurableProtocol.validate("eventInput", event));
        }
    }

    @Test
    void resourceLifecycleVectorsRequireStatusAndScopeWithoutInventedExecutionIds()
            throws Exception {
        try (var input = getClass().getResourceAsStream("/durable-protocol/vectors.json")) {
            var vectors =
                    DurableJson.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                            .required("resourceEvents");
            for (var event : vectors.required("valid")) {
                DurableProtocol.validate("event", event);
                var intent = ((ObjectNode) event).deepCopy();
                intent.remove(
                        List.of(
                                "schemaVersion",
                                "session",
                                "threadId",
                                "turnId",
                                "sequence",
                                "cursor"));
                DurableProtocol.validate("eventInput", intent);
            }
            for (var event : vectors.required("invalid"))
                assertThrows(
                        DurableException.class, () -> DurableProtocol.validate("event", event));
        }
    }

    @Test
    void eventFilterVectorsUseOneHierarchicalContractForQueriesAndSnapshots() throws Exception {
        try (var input = getClass().getResourceAsStream("/durable-protocol/vectors.json")) {
            var vectors =
                    DurableJson.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                            .required("eventFilters");
            for (String operation : List.of("events", "snapshot")) {
                for (String kind : List.of("valid", "invalid")) {
                    for (var filter : vectors.path(kind)) {
                        var payload =
                                (ObjectNode)
                                        DurableJson.value(
                                                Map.of(
                                                        "session",
                                                        Map.of(
                                                                "namespace",
                                                                "tenant",
                                                                "sessionId",
                                                                "session"),
                                                        "filter",
                                                        filter));
                        if (operation.equals("events")) {
                            payload.putNull("cursor");
                            payload.put("limit", 10);
                        }
                        if (kind.equals("valid"))
                            DurableProtocol.request(
                                    DurableProtocol.Endpoint.USER, operation, payload);
                        else
                            assertThrows(
                                    DurableException.class,
                                    () ->
                                            DurableProtocol.request(
                                                    DurableProtocol.Endpoint.USER,
                                                    operation,
                                                    payload));
                    }
                }
            }
        }
    }

    @Test
    void recoveryProtocolCarriesTheDurableBudgetAndRejectsIncompleteReports() throws Exception {
        try (var input = getClass().getResourceAsStream("/durable-protocol/vectors.json")) {
            var vector =
                    DurableJson.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                            .required("recovery");
            DurableProtocol.validateRequest(
                    DurableProtocol.Endpoint.BACKEND, vector.required("request"));
            var response =
                    DurableProtocol.response(
                            DurableProtocol.Endpoint.BACKEND,
                            "recordFailure",
                            vector.required("response"));
            var recovery = DurableJson.convert(response, DurableTypes.RecoveryView.class);
            assertEquals(1, recovery.failures());
            assertEquals(1200L, recovery.retryAt());
            assertTrue(recovery.retryAllowed());
            var missing = vector.required("request").deepCopy();
            ((ObjectNode) missing.path("payload").path("failure")).remove("policy");
            assertThrows(
                    DurableException.class,
                    () ->
                            DurableProtocol.validateRequest(
                                    DurableProtocol.Endpoint.BACKEND, missing));
            var contradictory = (ObjectNode) response.deepCopy();
            contradictory.put("retryable", false);
            assertThrows(
                    DurableException.class,
                    () -> DurableProtocol.validate("recovery", contradictory));
        }
    }

    @Test
    void frozenToolBindingsProtectTargetsAndParticipateInExecutionIdentity() {
        var binding =
                new ToolBinding(
                        "job",
                        "v1",
                        "hands",
                        DurableJson.value(
                                Map.of("name", "job", "parameters", Map.of("type", "object"))),
                        DurableJson.value(Map.of("target", "original")));
        DurableProtocol.validate("toolBinding", binding);
        String digest = binding.digest();
        ((ObjectNode) binding.target()).put("target", "changed");
        assertEquals(digest, binding.digest());
        var request =
                new DurableTypes.ExecutionRequest(
                        "execution",
                        new ThreadKey(new DurableTypes.SessionKey("tenant", "session"), "main"),
                        "turn",
                        binding.call("call", DurableJson.value(Map.of())));
        binding.verify(request);
        ExecutionMessages.validate(
                ExecutionMessages.command(ExecutionMessages.Kind.EXECUTE, request));
        var changed =
                new ToolBinding(
                        binding.name(),
                        binding.version(),
                        binding.executor(),
                        binding.schema(),
                        DurableJson.value(Map.of("target", "different")));
        assertThrows(DurableException.class, () -> changed.verify(request));
        var changedRequest =
                new DurableTypes.ExecutionRequest(
                        request.executionId(),
                        request.thread(),
                        request.turnId(),
                        changed.call("call", request.call().arguments()));
        assertFalse(DurableJson.hash(request).equals(DurableJson.hash(changedRequest)));
    }

    @Test
    void optionalMcpExecutionContractValidatesIdentityAndTerminalEvidence() {
        var identity =
                Map.of(
                        "version",
                        1,
                        "executionId",
                        "a".repeat(64),
                        "requestDigest",
                        "b".repeat(64));
        DurableProtocol.validate("mcpExecutionIdentity", identity);
        var observation = DurableJson.value(identity).deepCopy();
        var value = (ObjectNode) observation;
        value.put("state", "SUCCEEDED");
        assertThrows(
                DurableException.class,
                () -> DurableProtocol.validate("mcpExecutionObservation", value));
        value.set("result", DurableJson.value(Map.of("content", List.of(), "isError", false)));
        DurableProtocol.validate("mcpExecutionObservation", value);
        value.put("state", "FAILED");
        assertThrows(
                DurableException.class,
                () -> DurableProtocol.validate("mcpExecutionObservation", value));
        value.remove("result");
        value.put("state", "RUNNING");
        DurableProtocol.validate("mcpExecutionObservation", value);
        value.put("unrecognized", true);
        assertThrows(
                DurableException.class,
                () -> DurableProtocol.validate("mcpExecutionObservation", value));
        DurableProtocol.validate(
                "mcpRecoveryContract",
                Map.of("version", 1, "queryTool", "query", "idempotentStart", false));
        assertThrows(
                DurableException.class,
                () ->
                        DurableProtocol.validate(
                                "mcpRecoveryContract",
                                Map.of(
                                        "version",
                                        2,
                                        "queryTool",
                                        "query",
                                        "idempotentStart",
                                        true)));
    }

    @Test
    void independentJsonImplementationProducesTheSameExecutionIdentity() throws Exception {
        try (var stream = getClass().getResourceAsStream("/durable-protocol/vectors.json")) {
            var vectors =
                    DurableJson.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            var command =
                    DurableJson.convert(
                            vectors.required("executionCommand"), ExecutionMessages.Command.class);
            ExecutionMessages.validate(command);
            var request = command.request();
            assertEquals(
                    "exec_"
                            + DurableJson.hash(
                                    List.of(
                                            request.thread(),
                                            request.turnId(),
                                            request.call().callId())),
                    request.executionId());
            for (var invalid : vectors.required("invalidJson"))
                assertThrows(DurableException.class, () -> DurableJson.parse(invalid.asText()));
            for (var lossy : vectors.required("lossyNumbers"))
                assertThrows(
                        DurableException.class,
                        () -> DurableJson.hash(DurableJson.parse(lossy.asText())));
        }
    }

    @Test
    void handsOriginsAndApplicationPositionsHaveOneCurrentWireFormat() throws Exception {
        try (var input = getClass().getResourceAsStream("/durable-protocol/vectors.json")) {
            var vector =
                    DurableJson.parse(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                            .required("handsOrigins");
            DurableProtocol.validate("executionRequest", vector.required("request"));
            DurableProtocol.validate("appliedResult", vector.required("application"));
            for (var invalid : vector.required("invalidRequests"))
                assertThrows(
                        DurableException.class,
                        () -> DurableProtocol.validate("executionRequest", invalid));
            var request =
                    DurableJson.convert(
                            vector.required("request"), DurableTypes.ExecutionRequest.class);
            assertEquals("run-original", request.call().origin().runId());
            var changed =
                    new DurableTypes.ExecutionRequest(
                            request.executionId(),
                            request.thread(),
                            request.turnId(),
                            request.call()
                                    .withOrigin(
                                            new DurableTypes.RunStepRef(
                                                    "run-next", "step-resumed")));
            assertFalse(DurableJson.hash(request).equals(DurableJson.hash(changed)));
            assertEquals(ExecutionMessages.identity(request), ExecutionMessages.identity(changed));
        }
    }

    @Test
    void ambiguousJsonAndLossyNumbersCannotShareADurableIdentity() {
        for (var json : List.of("{\"id\":1,\"id\":2}", "{} {}", ""))
            assertEquals(
                    DurableException.Code.INVALID,
                    assertThrows(DurableException.class, () -> DurableJson.parse(json)).code());
        for (var json :
                List.of("9007199254740993", "9223372036854775807", "0.100000000000000001")) {
            var value = DurableJson.parse(json);
            assertEquals(
                    DurableException.Code.INVALID,
                    assertThrows(DurableException.class, () -> DurableJson.hash(value)).code());
        }
        assertEquals(
                DurableJson.hash(DurableJson.parse("1e20")),
                DurableJson.hash(DurableJson.parse("100000000000000000000")));
        assertEquals(DurableJson.hash(DurableJson.parse("0.1")), DurableJson.hash(0.1));
        DurableJson.hash("9223372036854775807");
    }

    @Test
    void packagedSchemaDoesNotDependOnTheCallingThreadsContextLoader() throws Exception {
        var owner = DurableProtocol.class.getClassLoader();
        var location = DurableProtocol.class.getProtectionDomain().getCodeSource().getLocation();
        try (var isolated =
                        new URLClassLoader(new URL[] {location}, owner) {
                            @Override
                            protected Class<?> loadClass(String name, boolean resolve)
                                    throws ClassNotFoundException {
                                if (!name.startsWith(DurableProtocol.class.getName()))
                                    return super.loadClass(name, resolve);
                                synchronized (getClassLoadingLock(name)) {
                                    var loaded = findLoadedClass(name);
                                    if (loaded == null) loaded = findClass(name);
                                    if (resolve) resolveClass(loaded);
                                    return loaded;
                                }
                            }
                        };
                var workerLoader =
                        new URLClassLoader(new URL[0], ClassLoader.getPlatformClassLoader())) {
            var thread = Thread.currentThread();
            var previous = thread.getContextClassLoader();
            try {
                thread.setContextClassLoader(workerLoader);
                var protocol = isolated.loadClass(DurableProtocol.class.getName());
                protocol.getMethod("validate", String.class, Object.class)
                        .invoke(
                                null,
                                "input",
                                Map.of(
                                        "schemaVersion",
                                        "1",
                                        "content",
                                        List.of(Map.of("type", "text", "text", "packaged"))));
            } finally {
                thread.setContextClassLoader(previous);
            }
        }
    }

    @Test
    void operationPayloadsAndResponsesFailClosed() {
        var endpoint = DurableProtocol.Endpoint.BACKEND;
        assertThrows(
                DurableException.class,
                () -> DurableProtocol.request(endpoint, "submit", Map.of()));
        assertThrows(
                DurableException.class, () -> DurableProtocol.request(endpoint, "start", Map.of()));
        assertThrows(
                DurableException.class,
                () ->
                        DurableProtocol.request(
                                endpoint,
                                "claim",
                                Map.of(
                                        "session",
                                        new DurableTypes.SessionKey("n", "s"),
                                        "owner",
                                        "o",
                                        "duration",
                                        "100")));
        var malformed =
                assertThrows(
                        DurableException.class,
                        () ->
                                DurableProtocol.response(
                                        endpoint,
                                        "commit",
                                        DurableJson.parse(
                                                "{\"version\":\"durable.v1\",\"result\":null}")));
        assertEquals(DurableException.Code.UNAVAILABLE, malformed.code());
        assertThrows(
                DurableException.class,
                () ->
                        DurableProtocol.validate(
                                "eventInput",
                                new DurableTypes.EventInput(
                                        "e", "message.completed", DurableJson.value(Map.of()))));
        DurableProtocol.validate(
                "eventInput",
                new DurableTypes.EventInput(
                        "e", "x.acme.notice", DurableJson.value(Map.of("value", 1))));
    }

    @Test
    void structuredContentRoundTripsWithoutNativeClasses() {
        var input =
                new DurableMessages.Input(
                        "1",
                        List.of(
                                new DurableMessages.Text("describe"),
                                new DurableMessages.Image(
                                        new DurableMessages.Url(
                                                "https://example.org/image.png", "image/png"))));
        DurableProtocol.validate("input", input);
        assertEquals(
                input, DurableJson.convert(DurableJson.value(input), DurableMessages.Input.class));
        DurableProtocol.validate(
                "artifact",
                new DurableMessages.Artifact(
                        "main",
                        "turn-1",
                        null,
                        null,
                        "a",
                        "https://example.org/a",
                        "text/plain",
                        null,
                        4L,
                        null));
    }

    @Test
    void javaAndTypescriptShareCanonicalDigestVector() throws Exception {
        try (var stream = getClass().getResourceAsStream("/durable-protocol/vectors.json")) {
            var vector =
                    DurableJson.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                            .get("canonical");
            assertEquals(vector.get("sha256").asText(), DurableJson.hash(vector.get("value")));
        }
    }

    @Test
    void schemaRejectsNumericPublicSequencesAndUnknownProtocolVersions() throws Exception {
        try (var stream = getClass().getResourceAsStream("/durable-protocol/schema.json")) {
            var root =
                    (ObjectNode)
                            DurableJson.parse(
                                    new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            assertTrue(root.has("$defs"));
            var registry =
                    SchemaRegistry.withDefaultDialect(
                            SpecificationVersion.DRAFT_2020_12,
                            builder ->
                                    builder.schemaIdResolvers(
                                            mappers ->
                                                    mappers.mapPrefix(
                                                            "https://agentscope.io/durable/v1/",
                                                            "classpath:durable-protocol/")));
            var schema =
                    registry.getSchema(
                            SchemaLocation.of(
                                    "https://agentscope.io/durable/v1/schema.json#/$defs/event"));
            var event =
                    (ObjectNode)
                            DurableJson.parse(
                                    """
                                    {"schemaVersion":"1","eventId":"e","session":{"namespace":"n","sessionId":"s"},
                                     "threadId":"main","turnId":"r","runId":null,"stepId":null,"sequence":"1","cursor":"opaque","type":"message.completed",
                                     "data":{"messageId":"m","attempt":1,"revision":0,"text":"ok"}}
                                    """);
            assertTrue(schema.validate(event).isEmpty());
            event.put("sequence", 1);
            assertFalse(schema.validate(event).isEmpty());
            var requests =
                    registry.getSchema(
                            SchemaLocation.of(
                                    "https://agentscope.io/durable/v1/schema.json#/$defs/request"));
            assertFalse(
                    requests.validate(
                                    DurableJson.parse(
                                            """
                                            {"version":"old","operation":"submit","payload":{}}
                                            """))
                            .isEmpty());
        }
    }
}
