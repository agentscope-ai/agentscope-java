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
package io.agentscope.harness.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import org.junit.jupiter.api.Test;

/**
 * Smoke tests for the local {@code streamEvents()} forwarding helper on {@link AgentSpawnTool}
 * (#3525): concurrent spawns of the same subagent type share one {@code source} path and must
 * stay attributable via the {@link AgentEvent#METADATA_CHILD_SESSION_ID} tag.
 */
class AgentSpawnToolLocalForwardingTest {

    @Test
    void tagLocalForwardedEvent_setsSourceAndChildSessionMetadata() {
        TextBlockDeltaEvent event = new TextBlockDeltaEvent(null, "b1", "hello");

        AgentEvent tagged =
                AgentSpawnTool.tagLocalForwardedEvent(
                        event, "parent-sess/researcher", "sub-abc-123");

        assertEquals("parent-sess/researcher", tagged.getSource());
        assertEquals("sub-abc-123", tagged.getMetadata().get(AgentEvent.METADATA_CHILD_SESSION_ID));
    }

    @Test
    void tagLocalForwardedEvent_preservesExistingMetadata() {
        TextBlockDeltaEvent event = new TextBlockDeltaEvent(null, "b1", "hello");
        event.withMetadataEntry("keep", "me");

        AgentEvent tagged =
                AgentSpawnTool.tagLocalForwardedEvent(event, "main/worker", "sub-def-456");

        assertEquals("me", tagged.getMetadata().get("keep"));
        assertEquals("sub-def-456", tagged.getMetadata().get(AgentEvent.METADATA_CHILD_SESSION_ID));
    }

    @Test
    void tagLocalForwardedEvent_skipsBlankChildSessionId() {
        TextBlockDeltaEvent event = new TextBlockDeltaEvent(null, "b1", "hello");

        AgentEvent tagged = AgentSpawnTool.tagLocalForwardedEvent(event, "main/worker", "  ");

        assertEquals("main/worker", tagged.getSource());
        assertTrue(
                tagged.getMetadata() == null
                        || !tagged.getMetadata().containsKey(AgentEvent.METADATA_CHILD_SESSION_ID));
    }

    @Test
    void tagLocalForwardedEvent_nullEventReturnsNull() {
        assertNull(AgentSpawnTool.tagLocalForwardedEvent(null, "main/worker", "sub-abc"));
    }
}
