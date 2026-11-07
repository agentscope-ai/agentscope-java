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
package io.agentscope.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.util.JsonException;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Payloads are parsed only by the readers that consume them, while validation of consumed payloads
 * and of registered extension codecs is unchanged.
 */
class SessionReaderPayloadTest {
    private static final String EXTENSION_TYPE = "test/reader-payload-3813";
    private static final List<String> validated = Collections.synchronizedList(new ArrayList<>());

    private final SessionKey key = new SessionKey("u", "a", "s");

    @BeforeAll
    static void registerExtensionCodec() {
        try {
            SessionEventCodecRegistry.defaultRegistry()
                    .register(
                            EXTENSION_TYPE,
                            new SessionEventCodecRegistry.Codec(
                                    data -> validated.add(String.valueOf(data.get("marker"))),
                                    (state, data) -> {}));
        } catch (IllegalArgumentException alreadyRegistered) {
            // The registry is process-wide; a rerun of this class keeps the first registration.
        }
    }

    private static SessionEvent event(long seq, String type, String payload) {
        return new SessionEvent(1, "e" + seq, seq, 1, type, "r", "t", false, payload);
    }

    private SessionLog logWith(SessionEvent... events) {
        var log = new InMemorySessionLogStore().open(key, null);
        log.commit(log.acquire("w", Duration.ofMinutes(2)), "b", 0, List.of(events));
        return log;
    }

    @Test
    void turnsIgnorePayloadsOfEventsTheyDoNotConsume() {
        var log =
                logWith(
                        event(1, "run/start", "{}"),
                        event(2, "step/start", "[1,2,3]"),
                        event(3, "tool/result", "\"not-an-object\""),
                        event(4, "run/end", "{\"status\":\"completed\"}"));

        var turns = SessionTurns.read(log);

        assertEquals(1, turns.turns().size());
        assertEquals(new SessionTurns.Turn("t", "r", "completed"), turns.latest());
        assertTrue(turns.latest().ended());
    }

    @Test
    void projectionIgnoresPayloadsOfEventsItDoesNotConsume() {
        var log =
                logWith(
                        event(1, "run/start", "{}"),
                        event(2, "model/chunk", "[1,2,3]"),
                        event(3, "turn/output", "null"));

        var projection = SessionProjection.read(log);

        assertEquals(Set.of("r"), projection.activeRuns());
        assertEquals(3, projection.asOfSeq());
        assertNull(projection.stateJson());
    }

    @Test
    void consumedPayloadsKeepTheirChecks() {
        assertThrows(
                JsonException.class,
                () -> SessionTurns.read(logWith(event(1, "run/end", "[1,2,3]"))));
        assertThrows(
                JsonException.class,
                () -> SessionProjection.read(logWith(event(1, "state/checkpoint", "[1,2,3]"))));
    }

    @Test
    void malformedJsonStillFailsAtConstruction() {
        assertThrows(JsonException.class, () -> event(1, "run/start", "{not-json"));
    }

    @Test
    void projectionValidatesRegisteredExtensionsForEveryEvent() {
        var log =
                logWith(
                        event(1, "run/start", "{}"),
                        event(2, EXTENSION_TYPE, "{\"marker\":\"seen\"}"));

        SessionProjection.read(log);

        assertTrue(validated.contains("seen"));
    }

    @Test
    void projectionStillAppliesRecoveryEvents() {
        var log =
                logWith(
                        event(1, "run/start", "{}"),
                        event(2, "tool/dispatch", "{\"toolCallId\":\"c1\"}"),
                        event(
                                3,
                                "recovery/applied",
                                "{\"interruptedRuns\":[\"r\"],\"resolvedToolCallIds\":[\"c1\"]}"));

        var projection = SessionProjection.read(log);

        assertTrue(projection.activeRuns().isEmpty());
        assertTrue(projection.uncertainToolCalls().isEmpty());
    }

    @Test
    void turnsStillReadConsumedInputMessages() {
        var msg = Msg.builder().id("m1").role(MsgRole.USER).textContent("hi").build();
        String payload = JsonUtils.getJsonCodec().toJson(Map.of("messages", List.of(msg)));

        var turns = SessionTurns.read(logWith(event(1, "input/received", payload)));

        assertEquals(1, turns.inputs().get("t").size());
        assertEquals("m1", turns.inputs().get("t").get(0).getId());
        assertEquals("hi", turns.inputs().get("t").get(0).getTextContent());
    }
}
