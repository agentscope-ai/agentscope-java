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

package io.agentscope.durable.codec.agentscope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.agentscope.core.agent.ThreadKey;
import io.agentscope.core.journal.InMemoryThreadJournalStore;
import io.agentscope.core.journal.JournalException;
import io.agentscope.core.journal.ThreadCheckpoints;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.durable.DurableJson;
import io.agentscope.durable.codec.NativeCheckpointCodec;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentScopeCheckpointCodecTest {
    @Test
    void packagedNativeSchemaMatchesCommittedFactsAndRejectsOtherVersions() throws Exception {
        try (var input =
                getClass()
                        .getResourceAsStream(
                                "/io/agentscope/core/journal/journal-event-v2.schema.json")) {
            assertNotNull(input);
            String text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            String id = "urn:agentscope:journal-event:2";
            var registry =
                    SchemaRegistry.withDefaultDialect(
                            SpecificationVersion.DRAFT_2020_12,
                            builder -> builder.schemas(Map.of(id, text)));
            var schema = registry.getSchema(SchemaLocation.of(id));
            var log =
                    new InMemoryThreadJournalStore()
                            .open(new ThreadKey("u", "a", "s", "main"), null);
            ThreadCheckpoints.initialize(
                    log, AgentState.builder().sessionId("s").build(), "snapshot", 0);
            var fact =
                    (ObjectNode)
                            DurableJson.parse(
                                    JsonUtils.getJsonCodec().toJson(log.readAfter(0, 1).get(0)));
            assertTrue(schema.validate(fact).isEmpty(), () -> schema.validate(fact).toString());
            fact.put("schemaVersion", 1);
            assertFalse(schema.validate(fact).isEmpty());
            fact.put("schemaVersion", 3);
            assertFalse(schema.validate(fact).isEmpty());
        }
    }

    @Test
    void rejectsUnknownRequiredNativeSchema() {
        var codec = new AgentScopeCheckpointCodec();
        assertThrows(
                JournalException.class,
                () ->
                        codec.inspect(
                                new NativeCheckpointCodec.Event(
                                        "unknown/required", "run", true, "{}")));
    }

    @Test
    void baselineUsesNativeStateAndRebindsIdentity() {
        var codec = new AgentScopeCheckpointCodec();
        var baseline =
                codec.baseline(
                        DurableJson.value(
                                        Map.of("stateJson", AgentState.builder().build().toJson()))
                                .toString(),
                        "target",
                        "main",
                        "owner",
                        true,
                        Map.of());
        var inspection =
                codec.inspect(
                        new NativeCheckpointCodec.Event(
                                baseline.type(), null, true, baseline.payloadJson()));
        assertTrue(inspection.checkpoint());
        assertTrue(inspection.settled());
        assertEquals(
                "target", DurableJson.parse(inspection.stateJson()).path("session_id").asText());
    }
}
