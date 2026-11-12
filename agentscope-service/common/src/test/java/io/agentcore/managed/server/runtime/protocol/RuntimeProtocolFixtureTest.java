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
package io.agentcore.managed.server.runtime.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** These wire fixtures are also consumed by the Go runtime contract tests. */
class RuntimeProtocolFixtureTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void versionOneFixturesRoundTripThroughTheSharedContracts() throws Exception {
        var types =
                Map.ofEntries(
                        Map.entry("bootstrap", RuntimeSessionBootstrap.class),
                        Map.entry("admission", RuntimeSessionAdmission.class),
                        Map.entry("ready", RuntimeSessionReady.class),
                        Map.entry("lifecycle", RuntimeSessionLifecycle.class),
                        Map.entry("lifecycle-receipt", RuntimeLifecycleReceipt.class),
                        Map.entry("command-receipt", RuntimeCommandReceipt.class),
                        Map.entry("turn", TurnCommand.class),
                        Map.entry("execution-request", RuntimeExecutionRequest.class),
                        Map.entry("execution-grant", RuntimeExecutionGrant.class),
                        Map.entry("execution-grant-overlay", RuntimeExecutionGrant.class),
                        Map.entry("interaction", InteractionCommand.class),
                        Map.entry("interaction-receipt", RuntimeInteractionReceipt.class),
                        Map.entry("fact", RuntimeFact.class),
                        Map.entry("fact-receipt", RuntimeFactReceipt.class),
                        Map.entry("tool-task", ToolTask.class),
                        Map.entry("tool-result", ToolResult.class),
                        Map.entry("usage", UsageReceipt.class));
        for (var entry : types.entrySet()) {
            byte[] fixture = fixture(entry.getKey());
            var decoded = mapper.readValue(fixture, entry.getValue());
            var encoded = mapper.writeValueAsBytes(decoded);
            assertEquals(decoded, mapper.readValue(encoded, entry.getValue()), entry.getKey());
        }
    }

    @Test
    void signalAnswersUseTheSharedInteractionContractWithoutRounding() throws Exception {
        var turn = mapper.readValue(fixture("turn"), TurnCommand.class);
        var command =
                new InteractionCommand(
                        turn.context(),
                        "signal",
                        Map.of(
                                "answers",
                                List.of(
                                        Map.of(
                                                "kind", "signal",
                                                "request_id", "pending-signal",
                                                "value", 9007199254740993L))));
        var decoded = mapper.readValue(mapper.writeValueAsBytes(command), InteractionCommand.class);
        assertEquals(command, decoded);
    }

    @Test
    void aFutureProtocolVersionIsRejectedDuringDecode() throws Exception {
        ObjectNode future = (ObjectNode) mapper.readTree(fixture("turn"));
        ((ObjectNode) future.get("context")).put("version", 2);
        var error =
                assertThrows(
                        JsonProcessingException.class,
                        () ->
                                mapper.readValue(
                                        mapper.writeValueAsBytes(future), TurnCommand.class));
        assertTrue(error.getMessage().contains("Unsupported runtime protocol version"));
    }

    @Test
    void backendSnapshotsPreserveTheirContractAndFreezeNestedContent() throws Exception {
        var original = mapper.readValue(fixture("bootstrap"), RuntimeSessionBootstrap.class);
        var contract = new LinkedHashMap<String, Object>();
        contract.put("definitionRevision", "team-v1");
        contract.put("optional", null);
        var configuration =
                Map.<String, Object>of(
                        "session",
                        Map.of("id", original.context().sessionId(), "ownerId", "owner"),
                        "contract",
                        contract);
        var bootstrap =
                new RuntimeSessionBootstrap(original.context(), "team", "revision", configuration);
        String digest = RuntimeBootstrapDigest.of(mapper, bootstrap);
        contract.put("definitionRevision", "changed-after-admission");
        assertEquals(digest, RuntimeBootstrapDigest.of(mapper, bootstrap));
        var encoded = mapper.writeValueAsBytes(bootstrap);
        assertEquals(bootstrap, mapper.readValue(encoded, RuntimeSessionBootstrap.class));
        var tree = (ObjectNode) mapper.readTree(encoded);
        assertEquals(
                "team-v1",
                tree.get("configuration").get("contract").get("definitionRevision").asText());
        assertTrue(tree.get("configuration").get("contract").get("optional").isNull());
        ((ObjectNode) tree.get("configuration").get("session")).put("id", "foreign-session");
        assertThrows(
                JsonProcessingException.class,
                () -> mapper.treeToValue(tree, RuntimeSessionBootstrap.class));
    }

    private byte[] fixture(String name) throws Exception {
        try (InputStream source =
                getClass().getResourceAsStream("/runtime-protocol/v1/" + name + ".json")) {
            assertNotNull(source, name);
            return source.readAllBytes();
        }
    }

    @Test
    void lifecycleDigestMatchesTheGoReceipt() throws Exception {
        var intent = mapper.readValue(fixture("lifecycle"), RuntimeSessionLifecycle.class);
        var receipt = mapper.readValue(fixture("lifecycle-receipt"), RuntimeLifecycleReceipt.class);
        assertEquals(receipt.payloadDigest(), RuntimeLifecycleDigest.of(mapper, intent));
    }

    @Test
    void canonicalDigestsMatchGoForUnicodeAndPreciseNumbers() throws Exception {
        var sample = mapper.readValue(fixture("canonical-digest"), Map.class);
        assertEquals(
                sample.get("digest"),
                RuntimePayloadDigest.of(mapper, sample.get("input")),
                new String(
                        mapper.copy()
                                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                                .writeValueAsBytes(sample.get("input")),
                        StandardCharsets.UTF_8));
    }
}
