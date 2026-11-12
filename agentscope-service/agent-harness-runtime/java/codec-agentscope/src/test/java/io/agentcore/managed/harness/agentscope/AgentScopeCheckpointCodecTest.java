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
package io.agentcore.managed.harness.agentscope;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentcore.managed.harness.runtime.NativeCheckpointCodec;
import io.agentscope.core.session.SessionEvent;
import io.agentscope.core.session.SessionEventTypes;
import io.agentscope.core.session.SessionLogException;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.util.JsonUtils;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentScopeCheckpointCodecTest {
    @Test
    void serviceCodecWorksWithoutBrainOrGlobalNativeRegistration() {
        assertThrows(
                ClassNotFoundException.class,
                () ->
                        Class.forName(
                                "io.agentcore.managed.server.runtime.managed.ManagedRuntimeWriter"));
        var codec = new AgentScopeCheckpointCodec();
        for (var value :
                Map.of(
                                AgentScopeEventSchemas.START,
                                        Map.of(
                                                "id",
                                                "frame",
                                                "startedAt",
                                                1,
                                                "zone",
                                                "UTC",
                                                "result",
                                                Map.of(),
                                                "stateJson",
                                                AgentState.builder().build().toJson()),
                                AgentScopeEventSchemas.END, Map.of("id", "frame"),
                                AgentScopeEventSchemas.MODEL,
                                        Map.of(
                                                "frame",
                                                "frame",
                                                "modelCallId",
                                                "model",
                                                "requestHash",
                                                "0".repeat(64),
                                                "slot",
                                                0))
                        .entrySet()) {
            String payload = JsonUtils.getJsonCodec().toJson(value.getValue());
            var nativeEvent =
                    new SessionEvent(
                            1, "event", 1, 1, value.getKey(), "run", "turn", true, payload);
            assertThrows(SessionLogException.class, () -> SessionEventTypes.validate(nativeEvent));
            assertDoesNotThrow(
                    () ->
                            codec.inspect(
                                    new NativeCheckpointCodec.Event(
                                            value.getKey(), "run", true, payload)));
            assertThrows(SessionLogException.class, () -> SessionEventTypes.validate(nativeEvent));
        }
        assertThrows(
                SessionLogException.class,
                () ->
                        codec.inspect(
                                new NativeCheckpointCodec.Event(
                                        "unknown/required", "run", true, "{}")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        codec.inspect(
                                new NativeCheckpointCodec.Event(
                                        AgentScopeEventSchemas.START, "run", true, "{}")));
    }

    @Test
    void baselineRebindsNativeStateAndPreservesItsContext() {
        var codec = new AgentScopeCheckpointCodec();
        String state = AgentState.builder().build().toJson();
        var baseline =
                codec.baseline(
                        JsonUtils.getJsonCodec().toJson(Map.of("stateJson", state)),
                        "target",
                        "owner",
                        true,
                        Map.of("operation_id", "fork"));
        var inspected =
                codec.inspect(
                        new NativeCheckpointCodec.Event(
                                baseline.type(), null, true, baseline.payloadJson()));
        assertTrue(inspected.checkpoint());
        assertTrue(inspected.settled());
        var rebound = JsonUtils.getJsonCodec().fromJson(inspected.stateJson(), Map.class);
        assertEquals("target", rebound.get("session_id"));
        assertEquals("owner", rebound.get("user_id"));
        assertEquals(
                JsonUtils.getJsonCodec().fromJson(state, Map.class).get("context"),
                rebound.get("context"));
    }
}
