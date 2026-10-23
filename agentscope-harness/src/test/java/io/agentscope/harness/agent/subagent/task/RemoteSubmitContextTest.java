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
package io.agentscope.harness.agent.subagent.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Wire shape of the {@code context} object sent with a remote task submission. */
class RemoteSubmitContextTest {

    @Test
    void toMapNestsCallerAttributesUnderAttributes() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("tenant", "acme");
        attributes.put("ticket_id", "INC-1");

        Map<String, Object> wire =
                RemoteSubmitContext.builder().userId("u-1").parentSessionId("sess-parent").stream(
                                true)
                        .detail("full")
                        .attributes(attributes)
                        .build()
                        .toMap();

        assertEquals("u-1", wire.get("user_id"));
        assertEquals("sess-parent", wire.get("parent_session_id"));
        assertEquals(true, wire.get("stream"));
        assertEquals("full", wire.get("detail"));
        assertEquals(attributes, wire.get("attributes"));
    }

    @Test
    void toMapOmitsAttributesWhenNoneProvided() {
        Map<String, Object> wire = RemoteSubmitContext.empty().toMap();

        assertFalse(wire.containsKey("attributes"));
        assertTrue(RemoteSubmitContext.empty().attributes().isEmpty());
    }

    @Test
    void attributesAreCopiedDefensively() {
        Map<String, Object> mutable = new HashMap<>();
        mutable.put("tenant", "acme");
        RemoteSubmitContext context = RemoteSubmitContext.builder().attributes(mutable).build();

        mutable.put("tenant", "changed");

        assertEquals("acme", context.attributes().get("tenant"));
    }
}
