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
package io.agentscope.harness.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.EventStreamingAgent;
import org.junit.jupiter.api.Test;

/** Tests that {@link HarnessAgent} exposes the v2 event-streaming capability contract. */
class HarnessAgentEventStreamingContractTest {

    @Test
    void implementsEventStreamingAgent() {
        assertTrue(EventStreamingAgent.class.isAssignableFrom(HarnessAgent.class));
    }

    @Test
    void exposesSessionScopedGetAgentStateOnAgent() throws NoSuchMethodException {
        assertTrue(
                HarnessAgent.class
                        .getMethod("getAgentState", String.class, String.class)
                        .getDeclaringClass()
                        .equals(HarnessAgent.class));
        assertTrue(
                HarnessAgent.class
                        .getMethod("getAgentState", io.agentscope.core.agent.RuntimeContext.class)
                        .getDeclaringClass()
                        .equals(HarnessAgent.class));
    }
}
