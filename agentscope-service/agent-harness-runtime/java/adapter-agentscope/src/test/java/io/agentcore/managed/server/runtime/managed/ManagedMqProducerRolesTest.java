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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentcore.managed.server.runtime.managed.ManagedMessageBus.Channel;
import org.junit.jupiter.api.Test;

class ManagedMqProducerRolesTest {
    @Test
    void bothProjectionPublisherRolesCanSendReferencesWithEitherQueueMode() {
        for (boolean sessionQueue : new boolean[] {false, true}) {
            for (String role : new String[] {"agent-service", "agent-harness-runtime"}) {
                assertTrue(
                        ManagedInfrastructureConfiguration.publishChannels(role, sessionQueue)
                                .contains(Channel.SESSION_PROJECTIONS),
                        role + " must authorize projection references");
            }
        }
    }

    @Test
    void queueModeOnlyEnablesTheInitialReadyLane() {
        for (boolean sessionQueue : new boolean[] {false, true}) {
            var channels =
                    ManagedInfrastructureConfiguration.publishChannels(
                            "agent-service", sessionQueue);
            assertTrue(channels.contains(Channel.TURNS));
            assertEquals(sessionQueue, channels.contains(Channel.SESSION_READY));
            // Session Service owns command publication and result-driven resume in both modes.
            assertTrue(channels.contains(Channel.TOOL_EXECUTE));
            assertTrue(channels.contains(Channel.TOOL_CONTROL));
            assertTrue(channels.contains(Channel.TOOL_EVENTS));
            assertTrue(channels.contains(Channel.TURN_RESUMES));
        }
    }

    @Test
    void handsCannotPublishPlatformProjectionFacts() {
        assertFalse(
                ManagedInfrastructureConfiguration.publishChannels("tool-worker", true)
                        .contains(Channel.SESSION_PROJECTIONS));
        assertThrows(
                IllegalArgumentException.class,
                () -> ManagedInfrastructureConfiguration.publishChannels("unknown", true));
    }
}
