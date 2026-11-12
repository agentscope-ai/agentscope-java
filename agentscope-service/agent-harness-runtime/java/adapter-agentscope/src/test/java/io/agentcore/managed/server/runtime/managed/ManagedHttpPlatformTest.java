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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentcore.managed.harness.runtime.ExecutionAccess;
import io.agentcore.managed.harness.runtime.ProtocolSchemas;
import io.agentcore.managed.harness.runtime.SessionExecutionAccess;
import io.agentcore.managed.harness.runtime.SessionServiceClient;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ManagedHttpPlatformTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutionAccess.Scope scope = new ExecutionAccess.Scope("t", "n", "s");
    private final ExecutionAccess.Owner owner = new ExecutionAccess.Owner("brain", "boot");

    @Test
    void uncertainClaimRetriesSameIdentityAndUsesAuthoritativeTurn() {
        var attempts = new CopyOnWriteArrayList<JsonNode>();
        var access =
                new SessionExecutionAccess(
                        (operation, value) -> {
                            var request = mapper.valueToTree(value);
                            attempts.add(request);
                            if (attempts.size() == 1)
                                throw new SessionServiceClient.Unavailable("lost response", null);
                            var response = ((ObjectNode) request).deepCopy();
                            response.remove(List.of("harnessType", "owner", "trigger"));
                            response.put("turnId", "authoritative-turn")
                                    .put("expiresAt", "2030-01-01T00:01:00Z");
                            response.set(
                                    "fence",
                                    mapper.valueToTree(
                                            new ExecutionAccess.Fence(owner, "2", "token")));
                            return response;
                        },
                        mapper);
        var claimed =
                access.claim(
                        new ExecutionAccess.Candidate(
                                scope,
                                "stale-turn",
                                mapper.valueToTree(
                                        Map.of(
                                                "kind",
                                                "ready",
                                                "messageId",
                                                "hint",
                                                "queueVersion",
                                                "1"))),
                        owner);
        assertEquals(attempts.get(0), attempts.get(1));
        assertEquals("authoritative-turn", claimed.turnId());
    }

    @Test
    void expiredRenewalNeverProducesALocalExecutionHandle() {
        var access =
                new SessionExecutionAccess(
                        (operation, value) -> {
                            var response = (ObjectNode) mapper.valueToTree(value);
                            return response.put("serverTime", "2030-01-01T00:01:00Z")
                                    .put("expiresAt", "2030-01-01T00:00:59Z");
                        },
                        mapper);
        var claim =
                new ExecutionAccess.Claim(
                        scope, "turn", new ExecutionAccess.Fence(owner, "1", "token"));
        assertThrows(
                IllegalStateException.class,
                () -> new ManagedHttpExecutions(access).attach(claim, () -> {}));
    }

    @Test
    void previewSplitsDeltaWithoutLosingUnicodeOrAdvancingAcrossEmptyChunks() throws Exception {
        var handles = mock(ManagedHttpExecutions.class);
        var managedScope = new ManagedScope("t", "n", "s");
        ObjectNode request =
                mapper.valueToTree(
                        Map.of(
                                "protocolVersion",
                                "0.1.0",
                                "scope",
                                scope,
                                "turnId",
                                "turn",
                                "fence",
                                new ExecutionAccess.Fence(owner, "1", "token")));
        when(handles.request(managedScope)).thenReturn(request);
        var frames = new CopyOnWriteArrayList<JsonNode>();
        var received = new CountDownLatch(2);
        var interests = new AtomicInteger();
        try (var previews =
                new ManagedHttpPreviews(
                        (operation, value) -> {
                            JsonNode body = mapper.valueToTree(value);
                            new ProtocolSchemas().request(operation, body);
                            if (operation.equals("previews/interest")) {
                                interests.incrementAndGet();
                                return mapper.valueToTree(Map.of("interested", true));
                            }
                            frames.add(body);
                            received.countDown();
                            return mapper.valueToTree(Map.of("accepted", true));
                        },
                        handles)) {
            assertNull(previews.begin(managedScope, "turn", "maintenance", 1, "COMPACTION"));
            var stream = previews.begin(managedScope, "turn", "model", 1, "REASONING");
            String content = "x".repeat(1023) + "😀" + "y".repeat(1200);
            stream.accept(new ManagedSessionPreviews.TextDelta("message", List.of("", content)));
            stream.accept(new ManagedSessionPreviews.TextDelta("message", List.of("end")));
            assertTrue(received.await(5, TimeUnit.SECONDS));
            assertEquals(1, interests.get());
            assertEquals("0", frames.get(0).path("sequence").asText());
            assertEquals("4", frames.get(1).path("sequence").asText());
            var joined = new StringBuilder();
            frames.get(0).required("chunks").forEach(part -> joined.append(part.asText()));
            assertEquals(content, joined.toString());
        }
    }
}
